package com.example.mydnd.rules;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class DiceService {

    private static final Set<Integer> SUPPORTED_SIDES =
            new HashSet<>(Arrays.asList(
                    4, 6, 8, 10, 12, 20, 100
            ));

    private final SecureRandom random = new SecureRandom();

    public RollResult roll(int sides, int modifier) {
        validateSides(sides);

        int natural = random.nextInt(sides) + 1;
        int total = natural + modifier;

        return new RollResult(
                sides,
                natural,
                modifier,
                total
        );
    }

    public CheckResult check(
            int sides,
            int modifier,
            int difficulty
    ) {
        RollResult roll = roll(sides, modifier);

        return new CheckResult(
                roll,
                difficulty,
                roll.getTotal() >= difficulty
        );
    }

    public int animationFace(int sides) {
        validateSides(sides);
        return random.nextInt(sides) + 1;
    }

    private void validateSides(int sides) {
        if (!SUPPORTED_SIDES.contains(sides)) {
            throw new IllegalArgumentException(
                    "Unsupported die: d" + sides
            );
        }
    }

    public static class RollResult {
        private final int sides;
        private final int natural;
        private final int modifier;
        private final int total;

        public RollResult(
                int sides,
                int natural,
                int modifier,
                int total
        ) {
            this.sides = sides;
            this.natural = natural;
            this.modifier = modifier;
            this.total = total;
        }

        public int getSides() {
            return sides;
        }

        public int getNatural() {
            return natural;
        }

        public int getModifier() {
            return modifier;
        }

        public int getTotal() {
            return total;
        }
    }

    public static class CheckResult {
        private final RollResult roll;
        private final int difficulty;
        private final boolean success;

        public CheckResult(
                RollResult roll,
                int difficulty,
                boolean success
        ) {
            this.roll = roll;
            this.difficulty = difficulty;
            this.success = success;
        }

        public RollResult getRoll() {
            return roll;
        }

        public int getDifficulty() {
            return difficulty;
        }

        public boolean isSuccess() {
            return success;
        }
    }
}
