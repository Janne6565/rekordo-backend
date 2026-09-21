package com.rekordo.controller.v1.implementation;

import com.rekordo.controller.v1.schema.MetadataApi;
import com.rekordo.model.core.AlbumCoverDto;
import com.rekordo.model.core.AlbumDto;
import com.rekordo.model.core.ArtistDto;
import com.rekordo.model.core.ArtistImageDto;
import com.rekordo.model.core.DiscographyDto;
import com.rekordo.model.core.ReleaseDto;
import com.rekordo.model.core.TracklistDto;
import com.rekordo.services.metadata.MetadataService;
import com.rekordo.services.metadata.TracklistService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@Validated
@RequiredArgsConstructor
public class MetadataController implements MetadataApi {

    private final MetadataService metadataService;
    private final TracklistService tracklistService;

    @Override
    public ResponseEntity<List<ReleaseDto>> search(String query, int limit) {
        return ResponseEntity.ok(metadataService.search(query.trim(), limit));
    }

    @Override
    public ResponseEntity<List<AlbumDto>> searchAlbums(String query, int limit) {
        return ResponseEntity.ok(metadataService.searchAlbums(query.trim(), limit));
    }

    @Override
    public ResponseEntity<List<ReleaseDto>> findByBarcode(String barcode) {
        return ResponseEntity.ok(metadataService.findByBarcode(barcode));
    }

    @Override
    public ResponseEntity<List<ArtistDto>> searchArtists(String query, int limit) {
        return ResponseEntity.ok(metadataService.searchArtists(query.trim(), limit));
    }

    @Override
    public ResponseEntity<ArtistImageDto> artistImage(UUID mbid) {
        return ResponseEntity.ok(metadataService.artistImage(mbid));
    }

    @Override
    public ResponseEntity<DiscographyDto> albumsOfArtist(UUID mbid, String primaryType, int limit) {
        MetadataService.Discography discography = metadataService.albumsOfArtist(mbid, primaryType, limit);
        return ResponseEntity.ok(new DiscographyDto(discography.albums(), discography.total()));
    }

    @Override
    public ResponseEntity<List<ReleaseDto>> releasesInGroup(String albumId, int limit) {
        return ResponseEntity.ok(metadataService.releasesInGroup(albumId, limit));
    }

    @Override
    public ResponseEntity<List<AlbumCoverDto>> albumCovers(List<String> albumIds) {
        return ResponseEntity.ok(metadataService.albumCovers(albumIds));
    }

    @Override
    public ResponseEntity<List<ReleaseDto>> getReleases(List<String> releaseIds) {
        return ResponseEntity.ok(metadataService.getReleases(releaseIds));
    }

    @Override
    public ResponseEntity<ReleaseDto> getRelease(String releaseId) {
        return ResponseEntity.ok(metadataService.getRelease(releaseId));
    }

    @Override
    public ResponseEntity<TracklistDto> getTracklist(String releaseId) {
        return ResponseEntity.ok(tracklistService.tracklist(releaseId));
    }
}
