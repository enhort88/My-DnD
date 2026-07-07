package com.example.mydnd.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.List;

public final class GameBackgroundManager {

    private static final String PREFS_NAME = "game_settings";
    private static final String KEY_BACKGROUND_NAME = "game_background_name";

    private static final String DEFAULT_BACKGROUND = "game_bg";
    private static final int MAX_BACKGROUNDS = 50;

    private GameBackgroundManager() {
    }

    public static List<BackgroundOption> getAvailableBackgrounds(
            Context context
    ) {
        List<BackgroundOption> result = new ArrayList<>();

        addIfExists(
                context,
                result,
                DEFAULT_BACKGROUND,
                "Фон 1"
        );

        for (int i = 2; i <= MAX_BACKGROUNDS; i++) {
            String resourceName = "game_bg" + i;

            addIfExists(
                    context,
                    result,
                    resourceName,
                    "Фон " + i
            );
        }

        return result;
    }

    private static void addIfExists(
            Context context,
            List<BackgroundOption> result,
            String resourceName,
            String title
    ) {
        int resourceId =
                context.getResources().getIdentifier(
                        resourceName,
                        "drawable",
                        context.getPackageName()
                );

        if (resourceId != 0) {
            result.add(
                    new BackgroundOption(
                            resourceName,
                            resourceId,
                            title
                    )
            );
        }
    }

    public static void saveSelectedBackground(
            Context context,
            String resourceName
    ) {
        getPreferences(context)
                .edit()
                .putString(
                        KEY_BACKGROUND_NAME,
                        resourceName
                )
                .apply();
    }

    public static String getSelectedBackgroundName(
            Context context
    ) {
        return getPreferences(context).getString(
                KEY_BACKGROUND_NAME,
                DEFAULT_BACKGROUND
        );
    }

    public static int getSelectedBackgroundIndex(
            Context context
    ) {
        String selectedName =
                getSelectedBackgroundName(context);

        List<BackgroundOption> backgrounds =
                getAvailableBackgrounds(context);

        for (int i = 0; i < backgrounds.size(); i++) {
            if (backgrounds
                    .get(i)
                    .getResourceName()
                    .equals(selectedName)) {

                return i;
            }
        }

        return 0;
    }

    public static String getSelectedBackgroundTitle(
            Context context
    ) {
        String selectedName =
                getSelectedBackgroundName(context);

        for (BackgroundOption option :
                getAvailableBackgrounds(context)) {

            if (option
                    .getResourceName()
                    .equals(selectedName)) {

                return option.getTitle();
            }
        }

        return "Фон 1";
    }

    public static void apply(
            Context context,
            ImageView targetView
    ) {
        if (targetView == null) {
            return;
        }

        String selectedName =
                getSelectedBackgroundName(context);

        int resourceId =
                context.getResources().getIdentifier(
                        selectedName,
                        "drawable",
                        context.getPackageName()
                );

        if (resourceId == 0) {
            resourceId =
                    context.getResources().getIdentifier(
                            DEFAULT_BACKGROUND,
                            "drawable",
                            context.getPackageName()
                    );
        }

        if (resourceId != 0) {
            targetView.setImageResource(resourceId);
        }
    }

    private static SharedPreferences getPreferences(
            Context context
    ) {
        return context.getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
        );
    }

    public static final class BackgroundOption {

        private final String resourceName;
        private final int resourceId;
        private final String title;

        public BackgroundOption(
                String resourceName,
                int resourceId,
                String title
        ) {
            this.resourceName = resourceName;
            this.resourceId = resourceId;
            this.title = title;
        }

        public String getResourceName() {
            return resourceName;
        }

        public int getResourceId() {
            return resourceId;
        }

        public String getTitle() {
            return title;
        }
    }
}