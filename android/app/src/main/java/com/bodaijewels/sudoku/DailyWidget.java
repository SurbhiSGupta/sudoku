package com.bodaijewels.sudoku;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.RemoteViews;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

// Home-screen widget: today's date and level, the streak, and a button that
// opens today's puzzle. The game saves what it knows (BodaiPlugin.updateWidget);
// the level comes from the weekday, so a new day shows up even before the app opens.
public class DailyWidget extends AppWidgetProvider {

    static final String PREFS = "bodai-widget";
    static final String OPEN_DAILY = "com.bodaijewels.sudoku.OPEN_DAILY";
    private static final String MIDNIGHT = "com.bodaijewels.sudoku.WIDGET_MIDNIGHT";

    // Same order as DAILY_LEVELS in index.html, Sunday first
    private static final String[] DAILY_LEVELS = { "zoomies", "easy", "medium", "medium", "hard", "medium", "hard" };

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        refresh(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (MIDNIGHT.equals(intent.getAction())) refresh(context);
        else super.onReceive(context, intent);
    }

    @Override
    public void onDisabled(Context context) {
        alarms(context).cancel(midnightIntent(context));
    }

    // Redraws every Bodai widget on the home screen
    static void refresh(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, DailyWidget.class));
        if (ids.length == 0) return;
        manager.updateAppWidget(ids, views(context));
        nextMidnight(context);
    }

    private static RemoteViews views(Context context) {
        Calendar now = Calendar.getInstance();
        String today = key(now);
        Calendar before = (Calendar) now.clone();
        before.add(Calendar.DAY_OF_MONTH, -1);
        String yesterday = key(before);

        SharedPreferences saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String date = saved.getString("date", "");
        boolean solved = saved.getBoolean("solved", false);
        int streak = 0;
        // A streak lasts while yesterday was solved and today is still open
        if (date.equals(today) || (date.equals(yesterday) && solved)) streak = saved.getInt("streak", 0);
        boolean isToday = date.equals(today);

        String level = DAILY_LEVELS[now.get(Calendar.DAY_OF_WEEK) - 1];
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_daily);
        views.setTextViewText(
            R.id.widget_day,
            new SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(now.getTime())
        );
        // Like the home screen: "Fetch ●●●○○ · 🔥 4"
        views.setTextViewText(R.id.widget_level, levelName(level) + "  " + dots(level) + (streak > 0 ? " · 🔥 " + streak : ""));

        // Two buttons in the layout, so each keeps its own light and dark colors
        boolean done = isToday && solved;
        views.setViewVisibility(R.id.widget_button, done ? View.GONE : View.VISIBLE);
        views.setViewVisibility(R.id.widget_done, done ? View.VISIBLE : View.GONE);
        if (done) {
            views.setTextViewText(R.id.widget_done, "✓ Solved in " + clock(saved.getInt("seconds", 0)));
        } else {
            int timer = isToday ? saved.getInt("timer", 0) : 0;
            views.setTextViewText(R.id.widget_button, timer > 0 ? "Continue · " + clock(timer) : "Play");
        }

        Intent open = new Intent(context, MainActivity.class)
            .setAction(OPEN_DAILY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        views.setOnClickPendingIntent(
            R.id.widget_root,
            PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)
        );
        return views;
    }

    // An inexact alarm is fine: a minute or two after midnight is soon enough
    private static void nextMidnight(Context context) {
        Calendar at = Calendar.getInstance();
        at.add(Calendar.DAY_OF_MONTH, 1);
        at.set(Calendar.HOUR_OF_DAY, 0);
        at.set(Calendar.MINUTE, 0);
        at.set(Calendar.SECOND, 30);
        alarms(context).set(AlarmManager.RTC, at.getTimeInMillis(), midnightIntent(context));
    }

    private static PendingIntent midnightIntent(Context context) {
        Intent tick = new Intent(context, DailyWidget.class).setAction(MIDNIGHT);
        return PendingIntent.getBroadcast(context, 0, tick, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static AlarmManager alarms(Context context) {
        return (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    }

    // Matches dayKey() in index.html
    private static String key(Calendar day) {
        return String.format(
            Locale.US,
            "%04d-%02d-%02d",
            day.get(Calendar.YEAR),
            day.get(Calendar.MONTH) + 1,
            day.get(Calendar.DAY_OF_MONTH)
        );
    }

    private static String levelName(String level) {
        switch (level) {
            case "easy":
                return "Stroll";
            case "medium":
                return "Fetch";
            case "hard":
                return "Sprint";
            case "zoomies":
                return "Zoomies";
            default:
                return "Puppy";
        }
    }

    // ●●●○○, like the level dots in the game
    private static String dots(String level) {
        int n = java.util.Arrays.asList("puppy", "easy", "medium", "hard", "zoomies").indexOf(level) + 1;
        StringBuilder out = new StringBuilder();
        for (int i = 1; i <= 5; i++) out.append(i <= n ? "●" : "○");
        return out.toString();
    }

    private static String clock(int seconds) {
        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }
}
