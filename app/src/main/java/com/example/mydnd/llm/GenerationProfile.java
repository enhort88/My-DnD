package com.example.mydnd.llm;

public class GenerationProfile {

    private final String name;
    private final int maxTokens;
    private final float temperature;
    private final float topP;
    private final int topK;
    private final float repeatPenalty;

    public GenerationProfile(
            String name,
            int maxTokens,
            float temperature,
            float topP,
            int topK,
            float repeatPenalty
    ) {
        this.name = name;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
        this.topP = topP;
        this.topK = topK;
        this.repeatPenalty = repeatPenalty;
    }

    public static GenerationProfile fast() {
        return new GenerationProfile(
                "Быстро",
                120,
                0.65f,
                0.85f,
                40,
                1.10f
        );
    }

    public static GenerationProfile normal() {
        return new GenerationProfile(
                "Нормально",
                140,
                0.75f,
                0.90f,
                40,
                1.12f
        );
    }

    public static GenerationProfile atmospheric() {
        return new GenerationProfile(
                "Атмосферно",
                320,
                0.85f,
                0.92f,
                50,
                1.15f
        );
    }

    public String getName() {
        return name;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public float getTemperature() {
        return temperature;
    }

    public float getTopP() {
        return topP;
    }

    public int getTopK() {
        return topK;
    }

    public float getRepeatPenalty() {
        return repeatPenalty;
    }

    public static GenerationProfile summary() {
        return new GenerationProfile(
                "Summary",
                80,
                0.10f,
                0.60f,
                10,
                1.05f
        );
    }
    public static GenerationProfile factExtraction() {
        return new GenerationProfile(
                "Fact extraction",
                20,
                0.01f,
                0.20f,
                5,
                1.00f
        );
    }

    public static GenerationProfile importanceFilter() {
        return new GenerationProfile(
                "Importance filter",
                12,
                0.01f,
                0.20f,
                5,
                1.00f
        );
    }
    public static GenerationProfile changeClassification() {
        return new GenerationProfile(
                "Change classification",
                32,
                0.01f,
                0.20f,
                5,
                1.00f
        );
    }
    public static GenerationProfile changeOperation() {
        return new GenerationProfile(
                "Change operation",
                16,
                0.01f,
                0.20f,
                5,
                1.00f
        );
    }
    public static GenerationProfile entityExtraction() {
        return new GenerationProfile(
                "Entity extraction",
                24,
                0.01f,
                0.20f,
                5,
                1.00f
        );
    }

}