package com.rekordo.services.sync;

import com.rekordo.model.core.SyncCopyDto;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Holds the Java merge to the same hand-authored contract the TypeScript implementations
 * are held to. The fixture is copied verbatim from the shared package; if the two
 * ever disagree, one of these suites fails — rather than a user's collection quietly
 * converging to different values on different devices.
 */
class CopyMergeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record MergeCase(String name, SyncCopyDto local, SyncCopyDto remote, SyncCopyDto expected) {}

    private static List<MergeCase> load() throws IOException {
        try (InputStream stream = CopyMergeTest.class.getResourceAsStream("/merge-fixture.json")) {
            assertThat(stream).as("merge-fixture.json on the test classpath").isNotNull();
            JsonNode root = MAPPER.readTree(stream);
            List<MergeCase> cases = new ArrayList<>();
            for (JsonNode node : root.get("cases")) {
                cases.add(new MergeCase(
                        node.get("name").asText(),
                        read(node.get("local")),
                        read(node.get("remote")),
                        read(node.get("expected"))));
            }
            assertThat(cases).as("fixture cases").isNotEmpty();
            return cases;
        }
    }

    private static SyncCopyDto read(JsonNode node) throws IOException {
        return node == null || node.isNull() ? null : MAPPER.treeToValue(node, SyncCopyDto.class);
    }

    @TestFactory
    Stream<DynamicTest> matchesTheSharedContract() throws IOException {
        return load().stream().map(testCase -> DynamicTest.dynamicTest(testCase.name(), () -> {
            assertThat(CopyMerge.merge(testCase.local(), testCase.remote()))
                    .as("merge(local, remote)")
                    .isEqualTo(testCase.expected());

            // Two peers merging the same pair from opposite directions must agree, or the
            // collection never converges.
            assertThat(CopyMerge.merge(testCase.remote(), testCase.local()))
                    .as("commutative")
                    .isEqualTo(testCase.expected());

            // Syncing twice with no edit in between must change nothing.
            SyncCopyDto once = CopyMerge.merge(testCase.local(), testCase.remote());
            assertThat(CopyMerge.merge(once, testCase.remote())).as("idempotent vs remote").isEqualTo(once);
            assertThat(CopyMerge.merge(once, testCase.local())).as("idempotent vs local").isEqualTo(once);
            assertThat(CopyMerge.merge(once, once)).as("idempotent vs itself").isEqualTo(once);
        }));
    }

    @Test
    void refusesToMergeTwoDifferentCopies() throws IOException {
        SyncCopyDto copy = load().getFirst().local();

        assertThatThrownBy(() -> CopyMerge.merge(copy, withId(copy, "different")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different copies");
    }

    @Test
    void stillReadsACopyPushedUnderTheOldFieldName() throws IOException {
        // A client deployed before the rename pushes `releaseMbid`. Reading that as absent
        // would detach the copy from its release, silently, inside a sync batch — so the
        // order of the client and server deploys must not matter.
        SyncCopyDto copy = MAPPER.readValue(
                """
                {"id":"c1","releaseMbid":"musicbrainz:r1","currency":"EUR","createdAt":1,"fieldClocks":{}}
                """,
                SyncCopyDto.class);

        assertThat(copy.releaseId()).isEqualTo("musicbrainz:r1");
    }

    @Test
    void refusesToMergeNothing() {
        assertThatThrownBy(() -> CopyMerge.merge(null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keepsRecordsThatExistOnOnlyOneSide() throws IOException {
        MergeCase shared = load().get(1);
        SyncCopyDto onlyLocal = withId(shared.local(), "local-only");
        SyncCopyDto onlyRemote = withId(shared.remote(), "remote-only");

        List<SyncCopyDto> merged =
                CopyMerge.mergeAll(List.of(shared.local(), onlyLocal), List.of(shared.remote(), onlyRemote));

        assertThat(merged).extracting(SyncCopyDto::id)
                .containsExactlyInAnyOrder("c1", "local-only", "remote-only");
        assertThat(merged).filteredOn(copy -> copy.id().equals("c1")).containsExactly(shared.expected());
    }

    private static SyncCopyDto withId(SyncCopyDto copy, String id) {
        return new SyncCopyDto(
                id,
                copy.releaseId(),
                copy.albumId(),
                copy.pendingBarcode(),
                copy.manualTitle(),
                copy.manualArtist(),
                copy.manualYear(),
                copy.manualLabel(),
                copy.manualCatalogNumber(),
                copy.manualFormat(),
                copy.condition(),
                copy.sleeveCondition(),
                copy.catalogArt(),
                copy.pricePaidCents(),
                copy.currency(),
                copy.purchasedOn(), copy.purchasedAt(), copy.notes(), copy.notesConflict(), copy.rating(),
                copy.hidden(), copy.sortIndex(), copy.createdAt(), copy.deletedAt(), copy.fieldClocks());
    }
}
