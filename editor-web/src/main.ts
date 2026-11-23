import './styles.css';
import { Editor } from '@tiptap/core';
import { EditorState } from '@tiptap/pm/state';
import StarterKit from '@tiptap/starter-kit';
import Placeholder from '@tiptap/extension-placeholder';
import TaskList from '@tiptap/extension-task-list';
import TaskItem from '@tiptap/extension-task-item';
import Link from '@tiptap/extension-link';
import Image from '@tiptap/extension-image';
import Highlight from '@tiptap/extension-highlight';
import Table from '@tiptap/extension-table';
import TableRow from '@tiptap/extension-table-row';
import TableHeader from '@tiptap/extension-table-header';
import TableCell from '@tiptap/extension-table-cell';
import Typography from '@tiptap/extension-typography';
import { Markdown } from 'tiptap-markdown';
import { OutlineCallout, preprocessOutlineCallouts, type CalloutType } from './callout';

interface NativeBridge {
  onReady?: () => void;
  onContentChanged?: (markdown: string) => void;
  onSelectionState?: (stateJson: string) => void;
  onHeadingsChanged?: (headingsJson: string) => void;
  onLinkClicked?: (url: string) => void;
  onImageClicked?: (src: string) => void;
  onRequestEditMode?: () => void;
}

interface HeadingItem {
  index: number;
  level: number;
  text: string;
}

declare global {
  interface Window {
    OutlineNative?: NativeBridge;
    OutlineEditor?: {
      loadDocument: (markdown: string) => void;
      setMarkdown: (markdown: string) => void;
      getMarkdown: () => string;
      setTheme: (isDark: boolean) => void;
      setAccentColor: (hex: string) => void;
      setReadOnly: (readOnly: boolean) => void;
      setEditable: (editable: boolean) => void;
      execCommand: (command: string, arg?: string) => void;
      insertImage: (src: string, alt?: string) => void;
      scrollToHeading: (index: number) => void;
      focusEditor: () => void;
    };
  }
}

function applyAccentColor(hex: string) {
  const clean = (hex || '').trim();
  if (!/^#([0-9a-fA-F]{6})$/.test(clean)) return;
  const r = parseInt(clean.slice(1, 3), 16);
  const g = parseInt(clean.slice(3, 5), 16);
  const b = parseInt(clean.slice(5, 7), 16);
  document.documentElement.style.setProperty('--accent', clean);
  document.documentElement.style.setProperty('--accent-soft', `rgba(${r}, ${g}, ${b}, 0.16)`);
}

// Apply initial theme and accent synchronously before first paint to prevent flash
(() => {
  try {
    const params = new URLSearchParams(window.location.search);
    if (params.get('theme') === 'dark') {
      document.documentElement.setAttribute('data-theme', 'dark');
    } else if (params.get('theme') === 'light') {
      document.documentElement.setAttribute('data-theme', 'light');
    }
    const accent = params.get('accent');
    if (accent) applyAccentColor(accent);
  } catch {
    // ignore
  }
})();

let isApplyingExternalContent = false;
let isReadOnlyMode = false;
let changeTimer: number | null = null;
let lastTapTime = 0;

function getMarkdownFromEditor(ed: Editor): string {
  try {
    const storage = ed.storage as unknown as {
      markdown?: { getMarkdown?: () => string };
    };
    return storage?.markdown?.getMarkdown?.() ?? '';
  } catch {
    return ed.getText();
  }
}

function emitHeadings(ed: Editor) {
  const headings: HeadingItem[] = [];
  let idx = 0;
  ed.state.doc.descendants((node) => {
    if (node.type.name === 'heading') {
      const text = node.textContent.trim();
      if (text.length > 0) {
        headings.push({
          index: idx,
          level: (node.attrs.level as number) || 1,
          text,
        });
      }
      idx++;
    }
  });
  window.OutlineNative?.onHeadingsChanged?.(JSON.stringify(headings));
}

function emitSelectionState(ed: Editor) {
  const state = {
    h1: ed.isActive('heading', { level: 1 }),
    h2: ed.isActive('heading', { level: 2 }),
    h3: ed.isActive('heading', { level: 3 }),
    bold: ed.isActive('bold'),
    italic: ed.isActive('italic'),
    strike: ed.isActive('strike'),
    code: ed.isActive('code'),
    highlight: ed.isActive('highlight'),
    bulletList: ed.isActive('bulletList'),
    orderedList: ed.isActive('orderedList'),
    taskList: ed.isActive('taskList'),
    blockquote: ed.isActive('blockquote'),
    callout: ed.isActive('callout'),
    codeBlock: ed.isActive('codeBlock'),
    table: ed.isActive('table'),
    canUndo: ed.can().undo(),
    canRedo: ed.can().redo(),
  };
  window.OutlineNative?.onSelectionState?.(JSON.stringify(state));
}

const editor = new Editor({
  element: document.getElementById('editor-root') as HTMLElement,
  extensions: [
    StarterKit.configure({
      heading: { levels: [1, 2, 3, 4] },
      codeBlock: { HTMLAttributes: { spellcheck: 'false' } },
    }),
    Placeholder.configure({
      placeholder: '开始记录你的想法…（支持工具栏排版，或输入 # / - / [] 自动转换）',
    }),
    TaskList,
    TaskItem.configure({
      nested: true,
    }),
    Link.configure({
      openOnClick: false,
      autolink: true,
      linkOnPaste: true,
    }),
    Image.configure({
      inline: false,
      allowBase64: true,
    }),
    Highlight,
    OutlineCallout,
    Table.configure({
      resizable: false,
    }),
    TableRow,
    TableHeader,
    TableCell,
    Typography,
    Markdown.configure({
      html: true,
      tightLists: true,
      bulletListMarker: '-',
      linkify: true,
      breaks: false,
      transformPastedText: true,
      transformCopiedText: true,
    }),
  ],
  editorProps: {
    handleClick(view, _pos, event) {
      const target = event.target as HTMLElement | null;
      if (!target) return false;

      // Link navigation (works in both read and edit mode)
      const anchor = target.closest('a') as HTMLAnchorElement | null;
      if (anchor && anchor.href) {
        event.preventDefault();
        window.OutlineNative?.onLinkClicked?.(anchor.getAttribute('href') || anchor.href);
        return true;
      }

      // Full-screen image lightbox
      if (target.tagName === 'IMG') {
        const img = target as HTMLImageElement;
        const src = img.getAttribute('src') || img.src;
        if (src) {
          event.preventDefault();
          window.OutlineNative?.onImageClicked?.(src);
          return true;
        }
      }

      // In Read-Only mode: allow interactive task checkbox toggling + double-tap to enter Edit mode
      if (isReadOnlyMode) {
        if (target.tagName === 'INPUT' && (target as HTMLInputElement).type === 'checkbox') {
          const li = target.closest('li[data-type="taskItem"]');
          if (li) {
            try {
              const domPos = view.posAtDOM(li, 0);
              const $pos = view.state.doc.resolve(domPos);
              for (let depth = $pos.depth; depth >= 0; depth--) {
                const node = $pos.node(depth);
                if (node.type.name === 'taskItem') {
                  const nodePos = $pos.before(depth);
                  const nextChecked = !node.attrs.checked;
                  const tr = view.state.tr.setNodeMarkup(nodePos, undefined, {
                    ...node.attrs,
                    checked: nextChecked,
                  });
                  view.dispatch(tr);
                  const md = getMarkdownFromEditor(editor);
                  window.OutlineNative?.onContentChanged?.(md);
                  return true;
                }
              }
            } catch {
              // ignore
            }
          }
          return true;
        }

        const now = Date.now();
        if (now - lastTapTime < 340) {
          lastTapTime = 0;
          window.OutlineNative?.onRequestEditMode?.();
          return true;
        }
        lastTapTime = now;
      }

      return false;
    },
  },
  onUpdate({ editor: ed }) {
    if (isApplyingExternalContent) return;
    emitSelectionState(ed);
    if (changeTimer !== null) {
      window.clearTimeout(changeTimer);
    }
    changeTimer = window.setTimeout(() => {
      const md = getMarkdownFromEditor(ed);
      emitHeadings(ed);
      window.OutlineNative?.onContentChanged?.(md);
    }, 220);
  },
  onSelectionUpdate({ editor: ed }) {
    emitSelectionState(ed);
  },
  onTransaction({ editor: ed }) {
    emitSelectionState(ed);
  },
});

window.OutlineEditor = {
  loadDocument(markdown: string) {
    isApplyingExternalContent = true;
    if (changeTimer !== null) {
      window.clearTimeout(changeTimer);
      changeTimer = null;
    }
    try {
      const preprocessed = preprocessOutlineCallouts(markdown || '');
      editor.commands.setContent(preprocessed, false);
      const freshState = EditorState.create({
        doc: editor.state.doc,
        plugins: editor.state.plugins,
      });
      editor.view.updateState(freshState);
      window.scrollTo(0, 0);
      emitSelectionState(editor);
      emitHeadings(editor);
    } finally {
      window.setTimeout(() => {
        isApplyingExternalContent = false;
      }, 60);
    }
  },

  setMarkdown(markdown: string) {
    isApplyingExternalContent = true;
    try {
      const preprocessed = preprocessOutlineCallouts(markdown || '');
      editor.commands.setContent(preprocessed, false);
      emitSelectionState(editor);
      emitHeadings(editor);
    } finally {
      window.setTimeout(() => {
        isApplyingExternalContent = false;
      }, 50);
    }
  },

  getMarkdown(): string {
    return getMarkdownFromEditor(editor);
  },

  setTheme(isDark: boolean) {
    document.documentElement.setAttribute('data-theme', isDark ? 'dark' : 'light');
  },

  setAccentColor(hex: string) {
    applyAccentColor(hex);
  },

  setReadOnly(readOnly: boolean) {
    isReadOnlyMode = readOnly;
    document.documentElement.setAttribute('data-readonly', readOnly ? 'true' : 'false');
    editor.setEditable(!readOnly);
  },

  setEditable(editable: boolean) {
    isReadOnlyMode = !editable;
    document.documentElement.setAttribute('data-readonly', editable ? 'false' : 'true');
    editor.setEditable(editable);
  },

  scrollToHeading(index: number) {
    const nodes = document.querySelectorAll('#editor-root .ProseMirror h1, #editor-root .ProseMirror h2, #editor-root .ProseMirror h3, #editor-root .ProseMirror h4');
    const target = nodes[index] as HTMLElement | undefined;
    if (target) {
      target.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  },

  focusEditor() {
    if (isReadOnlyMode) {
      isReadOnlyMode = false;
      document.documentElement.setAttribute('data-readonly', 'false');
      editor.setEditable(true);
    }
    editor.commands.focus('end');
  },

  insertImage(src: string, alt = '') {
    editor
      .chain()
      .focus()
      .setImage({ src, alt })
      .createParagraphNear()
      .run();
    const md = getMarkdownFromEditor(editor);
    window.OutlineNative?.onContentChanged?.(md);
  },

  execCommand(command: string, arg?: string) {
    const chain = editor.chain().focus();
    switch (command) {
      case 'h1':
        chain.toggleHeading({ level: 1 }).run();
        break;
      case 'h2':
        chain.toggleHeading({ level: 2 }).run();
        break;
      case 'h3':
        chain.toggleHeading({ level: 3 }).run();
        break;
      case 'paragraph':
        chain.setParagraph().run();
        break;
      case 'bold':
        chain.toggleBold().run();
        break;
      case 'italic':
        chain.toggleItalic().run();
        break;
      case 'strike':
        chain.toggleStrike().run();
        break;
      case 'code':
        chain.toggleCode().run();
        break;
      case 'highlight':
        chain.toggleHighlight().run();
        break;
      case 'bulletList':
        chain.toggleBulletList().run();
        break;
      case 'orderedList':
        chain.toggleOrderedList().run();
        break;
      case 'taskList':
        chain.toggleTaskList().run();
        break;
      case 'blockquote':
        chain.toggleBlockquote().run();
        break;
      case 'callout':
        chain.toggleCallout((arg as CalloutType) || 'info').run();
        break;
      case 'codeBlock':
        chain.toggleCodeBlock().run();
        break;
      case 'hr':
        chain.setHorizontalRule().run();
        break;
      case 'table':
        chain.insertTable({ rows: 3, cols: 3, withHeaderRow: true }).run();
        break;
      case 'addRowAfter':
        chain.addRowAfter().run();
        break;
      case 'deleteRow':
        chain.deleteRow().run();
        break;
      case 'addColumnAfter':
        chain.addColumnAfter().run();
        break;
      case 'deleteColumn':
        chain.deleteColumn().run();
        break;
      case 'deleteTable':
        chain.deleteTable().run();
        break;
      case 'link':
        if (arg) {
          chain.extendMarkRange('link').setLink({ href: arg }).run();
        } else {
          chain.unsetLink().run();
        }
        break;
      case 'undo':
        chain.undo().run();
        break;
      case 'redo':
        chain.redo().run();
        break;
    }
    emitSelectionState(editor);
    emitHeadings(editor);
  },
};

window.OutlineNative?.onReady?.();
