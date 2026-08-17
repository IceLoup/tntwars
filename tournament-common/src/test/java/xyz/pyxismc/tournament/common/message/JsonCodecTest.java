package xyz.pyxismc.tournament.common.message;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import xyz.pyxismc.tournament.common.model.PlayerMatchStats;
import xyz.pyxismc.tournament.common.model.TeamMatchStats;
import xyz.pyxismc.tournament.common.result.MatchResult;
import xyz.pyxismc.tournament.common.result.Placement;
import xyz.pyxismc.tournament.common.result.TeamResult;

class JsonCodecTest {

    private final JsonCodec codec = new JsonCodec();

    @Test
    void provisionRequestRoundTrip() {
        ProvisionRequest request = new ProvisionRequest(
                UUID.randomUUID(), "game", List.of(UUID.randomUUID(), UUID.randomUUID()));

        ProvisionRequest decoded = this.codec.fromJson(this.codec.toJson(request), ProvisionRequest.class);

        assertEquals(request, decoded);
    }

    @Test
    void matchStartMessageWithPlayersRoundTrip() {
        UUID teamA = UUID.randomUUID();
        UUID teamB = UUID.randomUUID();
        MatchStartMessage message = new MatchStartMessage(
                UUID.randomUUID(), "game-1234abcd", "Summer Cup",
                List.of(teamA, teamB),
                Map.of(teamA, List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
                        teamB, List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())),
                3);

        MatchStartMessage decoded = this.codec.fromJson(this.codec.toJson(message), MatchStartMessage.class);

        assertEquals(message, decoded);
    }

    @Test
    void matchResultMessageRoundTrip() {
        UUID matchId = UUID.randomUUID();
        UUID teamA = UUID.randomUUID();
        UUID teamB = UUID.randomUUID();
        UUID playerA = UUID.randomUUID();
        UUID playerB = UUID.randomUUID();
        Instant finishedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        MatchResult result = new MatchResult(
                matchId,
                List.of(new TeamResult(teamA, Placement.WINNER), new TeamResult(teamB, Placement.ELIMINATED)),
                Map.of(teamA, new TeamMatchStats(teamA, 5, 2),
                        teamB, new TeamMatchStats(teamB, 2, 5)),
                Map.of(playerA, new PlayerMatchStats(playerA, teamA, 5, 0, 1, 300),
                        playerB, new PlayerMatchStats(playerB, teamB, 1, 2, 0, 150)),
                Duration.ofMinutes(4),
                finishedAt);
        MatchResultMessage message = new MatchResultMessage(matchId, "game-1234abcd", result);

        MatchResultMessage decoded = this.codec.fromJson(this.codec.toJson(message), MatchResultMessage.class);

        assertEquals(message, decoded);
    }

    @Test
    void matchReadyMessageRoundTrip() {
        MatchReadyMessage message = new MatchReadyMessage(UUID.randomUUID(), "game-1234abcd");

        MatchReadyMessage decoded = this.codec.fromJson(this.codec.toJson(message), MatchReadyMessage.class);

        assertEquals(message, decoded);
    }
}