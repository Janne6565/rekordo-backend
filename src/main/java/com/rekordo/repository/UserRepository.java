package com.rekordo.repository;

import com.rekordo.entity.UserEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {

    /** Case-insensitive: e-mail is the login identifier and users do not type consistently. */
    @Query("SELECT u FROM UserEntity u WHERE LOWER(u.email) = LOWER(:email)")
    Optional<UserEntity> findByEmailIgnoreCase(String email);

    @Query("SELECT COUNT(u) > 0 FROM UserEntity u WHERE LOWER(u.email) = LOWER(:email)")
    boolean existsByEmailIgnoreCase(String email);

    /** Case-insensitive, like the e-mail: @Anna and @anna are the same handle. */
    @Query("SELECT u FROM UserEntity u WHERE LOWER(u.handle) = LOWER(:handle)")
    Optional<UserEntity> findByHandleIgnoreCase(@Param("handle") String handle);

    @Query("SELECT COUNT(u) > 0 FROM UserEntity u WHERE LOWER(u.handle) = LOWER(:handle)")
    boolean existsByHandleIgnoreCase(@Param("handle") String handle);

    /**
     * Handle prefix search, the autocomplete behind the Friends search field.
     *
     * <p>Handles only, never display names. A handle is chosen to be found by; somebody's
     * real name is not, and matching it would turn signing up into being listed.
     *
     * <p>Accounts with no settings row have never opted out, so the LEFT JOIN treats a
     * missing row as findable -- the same default the settings screen shows them.
     */
    @Query("""
            SELECT u FROM UserEntity u
            LEFT JOIN SharingSettingsEntity s ON s.userId = u.id
            WHERE u.handle IS NOT NULL
              AND LOWER(u.handle) LIKE LOWER(CONCAT(:prefix, '%'))
              AND (s.findable IS NULL OR s.findable = TRUE)
            ORDER BY LENGTH(u.handle) ASC, LOWER(u.handle) ASC
            """)
    List<UserEntity> searchByHandlePrefix(@Param("prefix") String prefix, Pageable pageable);

    List<UserEntity> findAllByIdIn(Collection<UUID> ids);
}
