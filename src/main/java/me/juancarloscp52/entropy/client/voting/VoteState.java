package me.juancarloscp52.entropy.client.voting;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Owned by the Minecraft client thread; independent of HUD and network transports. */
public final class VoteState {
    public static final int OPTION_COUNT = 4;
    private final Map<String, Integer> choices = new HashMap<>();
    private int pollId = -1;
    private int[] pending = new int[OPTION_COUNT];
    private int[] totals = new int[OPTION_COUNT];
    private int count;

    public void begin(int id) {
        pollId = id;
        choices.clear();
        pending = new int[OPTION_COUNT];
        totals = new int[OPTION_COUNT];
        count = 0;
    }

    public void end() {
        begin(-1);
    }

    public boolean vote(int option, String user) {
        if (pollId < 0 || option < 0 || option >= OPTION_COUNT || user == null || user.isBlank()) return false;
        Integer previous = choices.put(user, option);
        if (previous != null) {
            if (previous == option) return false;
            pending[previous]--;
            totals[previous] = Math.max(0, totals[previous] - 1);
        } else {
            count++;
        }
        pending[option]++;
        totals[option]++;
        return true;
    }

    public boolean remove(int option, String user) {
        if (pollId < 0 || user == null || !Integer.valueOf(option).equals(choices.get(user))) return false;
        choices.remove(user);
        pending[option]--;
        totals[option] = Math.max(0, totals[option] - 1);
        count = Math.max(0, count - 1);
        return true;
    }

    public void confirm(int id, int[] values, int total) {
        if (id != pollId || pollId < 0 || values == null || values.length != OPTION_COUNT || total < 0) return;
        if (Arrays.stream(values).anyMatch(value -> value < 0)) return;
        totals = values.clone();
        count = total;
    }

    public int[] drainDeltas() {
        int[] result = pending;
        pending = new int[OPTION_COUNT];
        return result;
    }

    public int pollId() { return pollId; }
    public int total() { return count; }
    public int[] totals() { return totals.clone(); }

    public static int parseOption(String message, int pollId, boolean alternate) {
        if (message == null || pollId < 0) return -1;
        String text = message.trim();
        if (text.length() != 1 || text.charAt(0) < '1' || text.charAt(0) > '8') return -1;
        int offset = alternate && pollId % 2 == 0 ? 5 : 1;
        int option = text.charAt(0) - '0' - offset;
        return option >= 0 && option < OPTION_COUNT ? option : -1;
    }
}
