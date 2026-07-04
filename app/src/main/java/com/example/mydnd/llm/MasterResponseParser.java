package com.example.mydnd.llm;

import android.util.Log;

import org.json.JSONObject;

public class MasterResponseParser {

    private static final String TAG =
            "MyDND_METADATA";


    public Result parse(
            String fullText
    ) {
        if (fullText == null) {
            return new Result(
                    null,
                    ""
            );
        }

        String normalized =
                fullText
                        .replace(
                                "\r\n",
                                "\n"
                        )
                        .trim();

        if (normalized.isEmpty()) {
            return new Result(
                    null,
                    ""
            );
        }

        int separatorIndex =
                normalized.indexOf(
                        "\n\n"
                );

        if (separatorIndex < 0) {
            Log.w(
                    TAG,
                    "Metadata separator not found"
            );

            return new Result(
                    null,
                    normalized
            );
        }

        String metadataJson =
                normalized
                        .substring(
                                0,
                                separatorIndex
                        )
                        .trim();

        String narrative =
                normalized
                        .substring(
                                separatorIndex + 2
                        )
                        .trim();

        if (!metadataJson.startsWith("{")) {
            Log.w(
                    TAG,
                    "Response does not start with metadata JSON"
            );

            return new Result(
                    null,
                    normalized
            );
        }

        try {
            JSONObject json =
                    new JSONObject(
                            metadataJson
                    );

            String type =
                    json.optString(
                            "type",
                            "NONE"
                    ).trim();

            String holder =
                    json.optString(
                            "holder",
                            "NONE"
                    ).trim();

            String name =
                    null;

            if (json.has("name")
                    && !json.isNull("name")) {

                name =
                        json.getString(
                                "name"
                        ).trim();

                if (name.isEmpty()) {
                    name =
                            null;
                }
            }

            Metadata metadata =
                    new Metadata(
                            type,
                            holder,
                            name,
                            metadataJson
                    );

            return new Result(
                    metadata,
                    narrative
            );

        } catch (Throwable throwable) {
            Log.e(
                    TAG,
                    "Failed to parse metadata: "
                            + metadataJson,
                    throwable
            );

            return new Result(
                    null,
                    narrative.isEmpty()
                            ? normalized
                            : narrative
            );
        }
    }


    public static class Result {

        private final Metadata metadata;

        private final String narrative;


        public Result(
                Metadata metadata,
                String narrative
        ) {
            this.metadata =
                    metadata;

            this.narrative =
                    narrative;
        }


        public Metadata getMetadata() {
            return metadata;
        }


        public String getNarrative() {
            return narrative;
        }


        public boolean hasMetadata() {
            return metadata != null;
        }
    }


    public static class Metadata {

        private final String type;

        private final String holder;

        private final String name;

        private final String rawJson;


        public Metadata(
                String type,
                String holder,
                String name,
                String rawJson
        ) {
            this.type =
                    type;

            this.holder =
                    holder;

            this.name =
                    name;

            this.rawJson =
                    rawJson;
        }


        public String getType() {
            return type;
        }


        public String getHolder() {
            return holder;
        }


        /*
         * Временно оставлено для совместимости со старым MainActivity.
         * Реальную операцию позже должна вычислять Java по старому и новому состоянию.
         */
        public String getOperation() {
            if ("PLAYER".equals(holder)) {
                return "ADD";
            }

            if ("WORLD".equals(holder)) {
                return "REMOVE";
            }

            return "NONE";
        }


        public String getName() {
            return name;
        }


        public String getRawJson() {
            return rawJson;
        }
    }
}