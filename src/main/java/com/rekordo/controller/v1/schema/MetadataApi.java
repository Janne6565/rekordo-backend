package com.rekordo.controller.v1.schema;

import com.rekordo.model.core.AlbumCoverDto;
import com.rekordo.model.core.AlbumDto;
import com.rekordo.model.core.ArtistDto;
import com.rekordo.model.core.ArtistImageDto;
import com.rekordo.model.core.DiscographyDto;
import com.rekordo.model.core.ReleaseDto;
import com.rekordo.model.core.TracklistDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.UUID;

/**
 * Release metadata from MusicBrainz, mirrored and cached locally.
 *
 * <p>Unauthenticated by design: the app is local-first and someone with no account must
 * still be able to search and scan. Abuse is bounded by a per-IP quota and the cache, not
 * by a login.
 */
@RequestMapping("/api/v1/metadata")
@Tag(name = "Metadata")
public interface MetadataApi {

    @GetMapping("/search")
    @Operation(summary = "Search releases by artist, title or catalog number",
            description = "One result per release and format, as the add flow lists them.")
    @ApiResponse(responseCode = "200", description = "Matching releases, possibly empty")
    @ApiResponse(responseCode = "429", description = "Per-IP rate limit exceeded")
    @ApiResponse(responseCode = "502", description = "MusicBrainz is unreachable")
    ResponseEntity<List<ReleaseDto>> search(
            @RequestParam("q") @NotBlank @Size(max = 200) String query,
            @RequestParam(value = "limit", defaultValue = "25") @Min(1) @Max(50) int limit);

    @GetMapping("/albums/search")
    @Operation(
            summary = "Search albums by artist or title",
            description = "One row per record, which is what the add flow should list. "
                    + "/search answers with pressings, and a record with ten of them fills "
                    + "the screen ten times over with rows nobody can tell apart until they "
                    + "have already chosen one. Answered from Apple Music, whose catalogue "
                    + "has no pressings to multiply by, and from Discogs grouped by master "
                    + "when Apple has nothing or this deployment carries no key -- the shape "
                    + "is the same either way and a client cannot tell which replied.\n\n"
                    + "`coverArtTemplate` is Apple's resizable artwork, carrying literal "
                    + "`{w}x{h}` placeholders a client substitutes for the size it needs; it "
                    + "is null for a Discogs answer, where `coverArtUrl` is the only image "
                    + "there is. Nothing in this response is mirrored, so an id here is not "
                    + "yet something the mirror can be asked about.")
    @ApiResponse(responseCode = "200", description = "Matching albums, possibly empty")
    @ApiResponse(responseCode = "429", description = "Per-IP rate limit exceeded")
    @ApiResponse(responseCode = "502", description = "Neither catalogue is reachable")
    ResponseEntity<List<AlbumDto>> searchAlbums(
            @RequestParam("q") @NotBlank @Size(max = 200) String query,
            @RequestParam(value = "limit", defaultValue = "25") @Min(1) @Max(50) int limit);

    @GetMapping("/barcode/{barcode}")
    @Operation(summary = "Look up releases by barcode",
            description = "Answered from the local mirror when the barcode has been seen before.")
    @ApiResponse(responseCode = "200", description = "Matching releases, possibly empty")
    @ApiResponse(responseCode = "429", description = "Per-IP rate limit exceeded")
    ResponseEntity<List<ReleaseDto>> findByBarcode(
            @PathVariable @Pattern(regexp = "\\d{8,14}", message = "A barcode is 8 to 14 digits") String barcode);

    @GetMapping("/artists")
    @Operation(
            summary = "Search artists by name",
            description = "Separate from /search on purpose. The release index matches on title, so a bare "
                    + "band name there returns records *called* that name and nothing by the band. Results "
                    + "carry MusicBrainz's own match score, highest first.")
    @ApiResponse(responseCode = "200", description = "Matching artists, possibly empty")
    @ApiResponse(responseCode = "429", description = "Per-IP rate limit exceeded")
    @ApiResponse(responseCode = "502", description = "MusicBrainz is unreachable")
    ResponseEntity<List<ArtistDto>> searchArtists(
            @RequestParam("q") @NotBlank @Size(max = 200) String query,
            @RequestParam(value = "limit", defaultValue = "5") @Min(1) @Max(25) int limit);

    @GetMapping("/artists/{mbid}/image")
    @Operation(
            summary = "One artist's portrait",
            description = "Its own endpoint, one artist at a time, because the first answer for any "
                    + "artist costs two paced upstream calls — MusicBrainz for the `discogs` URL "
                    + "relation that identifies the artist exactly, then Discogs for the picture. "
                    + "Folding it into /artists would hold a list of five behind the slowest of them. "
                    + "Answers are kept, so the second time is free. `imageUrl` is null when the "
                    + "artist genuinely has no picture; clients fall back to the initial.")
    @ApiResponse(responseCode = "200", description = "The portrait, or null when there is none")
    @ApiResponse(responseCode = "429", description = "Per-IP rate limit exceeded")
    ResponseEntity<ArtistImageDto> artistImage(@PathVariable UUID mbid);

    @GetMapping("/artists/{mbid}/albums")
    @Operation(
            summary = "One artist's discography, by primary type",
            description = "`total` is how many the query matched upstream, not the size of this page — a "
                    + "client showing \"Albums 51\" is telling the truth on a page of 25. Omit `type` for "
                    + "everything.")
    @ApiResponse(responseCode = "200", description = "A page of the discography")
    @ApiResponse(responseCode = "502", description = "MusicBrainz is unreachable")
    ResponseEntity<DiscographyDto> albumsOfArtist(
            @PathVariable UUID mbid,
            @RequestParam(value = "type", required = false) @Size(max = 30) String primaryType,
            @RequestParam(value = "limit", defaultValue = "25") @Min(1) @Max(100) int limit);

    @GetMapping("/albums/{albumId}/releases")
    @Operation(
            summary = "Every pressing of one album",
            description = "Bitches Brew has 47, so this pages rather than pretending the list is short.")
    @ApiResponse(responseCode = "200", description = "The pressings, possibly empty")
    @ApiResponse(responseCode = "502", description = "MusicBrainz is unreachable")
    ResponseEntity<List<ReleaseDto>> releasesInGroup(
            @PathVariable("albumId") @NotBlank @Size(max = 120) String albumId,
            @RequestParam(value = "limit", defaultValue = "25") @Min(1) @Max(100) int limit);

    @GetMapping("/albums/covers")
    @Operation(
            summary = "The artwork for a set of albums",
            description = "For screens that hold albums rather than pressings — a wishlist entry names an "
                    + "album, so it carries no cover of its own. Answered from the local mirror, never "
                    + "from a catalogue: a list of thirty rows is one request, not thirty upstream "
                    + "lookups. `coverArtUrl` is null where nothing known has a cover, and the URL may "
                    + "still 404, so clients keep their placeholder either way. Ids are echoed back "
                    + "exactly as they were asked for; hand-entered `local:` albums are left out of the "
                    + "response entirely.")
    @ApiResponse(responseCode = "200", description = "One entry per resolvable album, in the order asked")
    ResponseEntity<List<AlbumCoverDto>> albumCovers(
            @RequestParam("albumId") @Size(min = 1, max = 100) List<@NotBlank @Size(max = 120) String> albumIds);

    @GetMapping("/releases")
    @Operation(
            summary = "A set of releases, from the mirror only",
            description = "What a device that has just signed in needs: its copies arrive over sync as "
                    + "release *references*, and the metadata behind them is a shared cache that "
                    + "travels separately. Answered from the local mirror and never from a catalogue — "
                    + "a collection of two hundred records must not become two hundred upstream "
                    + "lookups — so an id the mirror has never seen is simply absent from the response "
                    + "rather than a 404. Hand-entered `local:` releases are left out too: they are "
                    + "derived from the copy itself and were never in any catalogue.")
    @ApiResponse(responseCode = "200", description = "The releases the mirror holds, in no guaranteed order")
    ResponseEntity<List<ReleaseDto>> getReleases(
            @RequestParam("releaseId") @Size(min = 1, max = 100) List<@NotBlank @Size(max = 120) String> releaseIds);

    @GetMapping("/releases/{releaseId}")
    @Operation(summary = "Full detail for one release, including its cover theme",
            description = "The cover palette is sampled on the first lookup and reused afterwards.")
    @ApiResponse(responseCode = "200", description = "The release")
    @ApiResponse(responseCode = "404", description = "No such release")
    ResponseEntity<ReleaseDto> getRelease(
            @PathVariable("releaseId") @NotBlank @Size(max = 120) String releaseId);

    @GetMapping("/releases/{releaseId}/tracks")
    @Operation(
            summary = "One release's tracklist",
            description = "Read from the mirror, and from MusicBrainz exactly once per release — "
                    + "the upstream is paced at one request per second, so a detail sheet that "
                    + "re-asked on every open would queue behind every search in the app. "
                    + "Numbering is the catalogue's own and is never recomputed: a double LP reads "
                    + "A1 through D6. Durations are milliseconds and are routinely absent for "
                    + "individual tracks. A track carries an artist only where it differs from the "
                    + "release's, which is a compilation and nothing else.\n\n"
                    + "A release with no tracklist is still a 200: `unavailableReason` says why, and "
                    + "the count the mirror already knew comes back with it, because the sheet states "
                    + "that count whether or not the titles ever arrive. Those reasons are permanent "
                    + "and offer nothing to retry. The catalogue failing to answer is the opposite "
                    + "case and is a 502.")
    @ApiResponse(responseCode = "200", description = "The tracklist, or the reason there is none")
    @ApiResponse(responseCode = "502", description = "The catalogue did not answer — worth retrying")
    ResponseEntity<TracklistDto> getTracklist(
            @PathVariable("releaseId") @NotBlank @Size(max = 120) String releaseId);
}
