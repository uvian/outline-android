package com.outline.notes.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.outline.notes.MainActivity
import com.outline.notes.OutlineApp
import com.outline.notes.R
import kotlinx.coroutines.launch

class QuickNoteWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val pendingResult = goAsync()
        val app = context.applicationContext as? OutlineApp
        if (app == null) {
            pendingResult.finish()
            return
        }

        app.appScope.launch {
            try {
                val settings = app.preferences.getSettings()
                val recent = app.database.outlineDao().getRecentDocumentsSnapshot(3)

                for (widgetId in appWidgetIds) {
                    val views = RemoteViews(context.packageName, R.layout.widget_quick_note)
                    views.setTextViewText(
                        R.id.widget_workspace_title,
                        settings.workspaceName.ifBlank { "Outline Notes" },
                    )

                    // Quick capture button
                    val createIntent = Intent(context, MainActivity::class.java).apply {
                        action = MainActivity.ACTION_QUICK_CREATE
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    val createPending = PendingIntent.getActivity(
                        context,
                        1001,
                        createIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    views.setOnClickPendingIntent(R.id.widget_quick_add, createPending)

                    // Open app root on header tap
                    val homeIntent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    val homePending = PendingIntent.getActivity(
                        context,
                        1002,
                        homeIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    views.setOnClickPendingIntent(R.id.widget_workspace_title, homePending)

                    val slotIds = listOf(R.id.widget_note_1, R.id.widget_note_2, R.id.widget_note_3)
                    if (recent.isEmpty()) {
                        views.setViewVisibility(R.id.widget_note_1, View.VISIBLE)
                        views.setTextViewText(R.id.widget_note_1, "点击右上角「+ 速记」立即记笔记")
                        views.setOnClickPendingIntent(R.id.widget_note_1, createPending)
                        views.setViewVisibility(R.id.widget_note_2, View.GONE)
                        views.setViewVisibility(R.id.widget_note_3, View.GONE)
                    } else {
                        slotIds.forEachIndexed { idx, viewId ->
                            val doc = recent.getOrNull(idx)
                            if (doc != null) {
                                views.setViewVisibility(viewId, View.VISIBLE)
                                views.setTextViewText(viewId, "• ${doc.title.ifBlank { "无标题笔记" }}")
                                val docIntent = Intent(context, MainActivity::class.java).apply {
                                    action = MainActivity.ACTION_OPEN_DOC
                                    putExtra(MainActivity.EXTRA_DOC_ID, doc.id)
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                }
                                val docPending = PendingIntent.getActivity(
                                    context,
                                    2000 + idx,
                                    docIntent,
                                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                                )
                                views.setOnClickPendingIntent(viewId, docPending)
                            } else {
                                views.setViewVisibility(viewId, View.GONE)
                            }
                        }
                    }

                    appWidgetManager.updateAppWidget(widgetId, views)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        fun requestUpdate(context: Context) {
            runCatching {
                val manager = AppWidgetManager.getInstance(context)
                val component = ComponentName(context, QuickNoteWidgetProvider::class.java)
                val ids = manager.getAppWidgetIds(component)
                if (ids.isNotEmpty()) {
                    val intent = Intent(context, QuickNoteWidgetProvider::class.java).apply {
                        action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                    }
                    context.sendBroadcast(intent)
                }
            }
        }
    }
}
