package me.juancarloscp52.entropy.client.voting;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VoteStateTest {
    @Test void conversationAndWrongVotingRoundAreIgnored() {
        assertEquals(-1, VoteState.parseOption("a", 2, true));
        assertEquals(-1, VoteState.parseOption("１", 2, true));
        assertEquals(-1, VoteState.parseOption(null, 2, true));
        assertEquals(-1, VoteState.parseOption("1", -1, false));
        assertEquals(-1, VoteState.parseOption("1", 2, true));
        assertEquals(0, VoteState.parseOption(" 5 ", 2, true));
        assertEquals(3, VoteState.parseOption("8", 2, true));
        assertEquals(0, VoteState.parseOption("1", 3, true));
        assertEquals(0, VoteState.parseOption("1", 2, false));
    }

    @Test void changingAndRemovingVotePreservesNegativeDeltas() {
        VoteState state = new VoteState();
        assertFalse(state.vote(0, "twitch:alice"));
        state.begin(2);
        assertTrue(state.vote(0, "twitch:alice"));
        assertArrayEquals(new int[]{1, 0, 0, 0}, state.drainDeltas());
        assertTrue(state.vote(3, "twitch:alice"));
        assertFalse(state.vote(3, "twitch:alice"));
        assertArrayEquals(new int[]{-1, 0, 0, 1}, state.drainDeltas());
        assertEquals(1, state.total());
        assertFalse(state.remove(1, "twitch:alice"));
        assertTrue(state.remove(3, "twitch:alice"));
        assertArrayEquals(new int[]{0, 0, 0, -1}, state.drainDeltas());
        assertEquals(0, state.total());
    }

    @Test void confirmationCopiesDataAndRejectsOtherPolls() {
        VoteState state = new VoteState();
        state.begin(3);
        int[] totals = {2, 4, 0, 1};
        state.confirm(3, totals, 7);
        totals[0] = 900;
        int[] snapshot = state.totals();
        snapshot[1] = 900;
        assertArrayEquals(new int[]{2, 4, 0, 1}, state.totals());
        state.confirm(2, new int[]{9, 9, 9, 9}, 36);
        assertEquals(7, state.total());
        state.end();
        assertFalse(state.vote(0, "twitch:alice"));
        state.begin(4);
        assertEquals(0, state.total());
        assertTrue(state.vote(1, "twitch:alice"));
    }
}
