import { Node, mergeAttributes } from '@tiptap/core';

export type CalloutType = 'info' | 'tip' | 'warning';

declare module '@tiptap/core' {
  interface Commands<ReturnType> {
    callout: {
      setCallout: (type?: CalloutType) => ReturnType;
      toggleCallout: (type?: CalloutType) => ReturnType;
    };
  }
}

export const OutlineCallout = Node.create({
  name: 'callout',
  group: 'block',
  content: 'block+',
  defining: true,

  addAttributes() {
    return {
      type: {
        default: 'info',
        parseHTML: (element) => element.getAttribute('data-callout-type') || 'info',
        renderHTML: (attributes) => ({
          'data-callout-type': attributes.type || 'info',
        }),
      },
    };
  },

  parseHTML() {
    return [{ tag: 'div[data-callout-type]' }];
  },

  renderHTML({ HTMLAttributes }) {
    const type = HTMLAttributes['data-callout-type'] || 'info';
    return [
      'div',
      mergeAttributes(HTMLAttributes, {
        class: `outline-callout callout-${type}`,
      }),
      0,
    ];
  },

  addCommands() {
    return {
      setCallout:
        (type: CalloutType = 'info') =>
        ({ commands }) =>
          commands.wrapIn(this.name, { type }),
      toggleCallout:
        (type: CalloutType = 'info') =>
        ({ commands }) =>
          commands.toggleWrap(this.name, { type }),
    };
  },

  addStorage() {
    return {
      markdown: {
        serialize(
          state: {
            write: (s: string) => void;
            renderContent: (node: unknown) => void;
            closeBlock: (node: unknown) => void;
          },
          node: { attrs: { type?: string } },
        ) {
          const type = node.attrs.type || 'info';
          state.write(`:::${type}\n`);
          state.renderContent(node);
          state.write(':::\n');
          state.closeBlock(node);
        },
        parse: {
          // Handled cleanly via preprocessOutlineCallouts before setContent
        },
      },
    };
  },
});

/**
 * Converts Outline's native Markdown notice syntax:
 *   :::info
 *   content
 *   :::
 * into HTML wrapper blocks that Tiptap parses into OutlineCallout nodes,
 * while ignoring fenced code blocks.
 */
export function preprocessOutlineCallouts(markdown: string): string {
  if (!markdown || !markdown.includes(':::')) return markdown;

  const lines = markdown.split('\n');
  const out: string[] = [];
  let inCodeFence = false;
  let inCallout = false;
  let calloutType: string = 'info';
  let calloutLines: string[] = [];

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const trimmed = line.trim();

    if (trimmed.startsWith('```') || trimmed.startsWith('~~~')) {
      inCodeFence = !inCodeFence;
      if (inCallout) {
        calloutLines.push(line);
      } else {
        out.push(line);
      }
      continue;
    }

    if (!inCodeFence && !inCallout) {
      const openMatch = /^:::(info|tip|warning|note)\s*$/i.exec(trimmed);
      if (openMatch) {
        inCallout = true;
        const rawType = openMatch[1].toLowerCase();
        calloutType = rawType === 'note' ? 'info' : rawType;
        calloutLines = [];
        continue;
      }
    } else if (!inCodeFence && inCallout) {
      if (trimmed === ':::') {
        const inner = calloutLines.join('\n').trim();
        out.push(`<div data-callout-type="${calloutType}">\n\n${inner}\n\n</div>`);
        inCallout = false;
        calloutLines = [];
        continue;
      }
      calloutLines.push(line);
      continue;
    }

    out.push(line);
  }

  // Unclosed callout fallback: keep verbatim
  if (inCallout) {
    out.push(`:::${calloutType}`);
    out.push(...calloutLines);
  }

  return out.join('\n');
}
