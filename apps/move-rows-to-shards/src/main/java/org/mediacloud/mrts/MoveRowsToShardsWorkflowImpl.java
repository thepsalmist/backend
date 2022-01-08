package org.mediacloud.mrts;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;

public class MoveRowsToShardsWorkflowImpl implements MoveRowsToShardsWorkflow {

    private static final Logger log = LoggerFactory.getLogger(MoveRowsToShardsWorkflowImpl.class);

    private static final String START_ID_MARKER = "**START_ID**";
    private static final String END_ID_MARKER = "**END_ID**";

    private static final RetryOptions DEFAULT_RETRY_OPTIONS = RetryOptions.newBuilder().setInitialInterval(Duration.ofSeconds(1)).setBackoffCoefficient(2).setMaximumInterval(Duration.ofHours(2)).setMaximumAttempts(1000).build();

    private final MinMaxTruncateActivities minMaxTruncate = Workflow.newActivityStub(MinMaxTruncateActivities.class, ActivityOptions.newBuilder().setTaskQueue(Shared.TASK_QUEUE)
            // If we need to rerun everything, min. / max. value or TRUNCATE might take a while to find because
            // we'll be skipping a bunch of dead tuples
            .setStartToCloseTimeout(Duration.ofHours(2)).setRetryOptions(DEFAULT_RETRY_OPTIONS).build());

    private final MoveRowsActivities moveRows = Workflow.newActivityStub(MoveRowsActivities.class, ActivityOptions.newBuilder().setTaskQueue(Shared.TASK_QUEUE)
            // We should be able to hopefully move at least a chunk every 4 days (assuming that everything will
            // be running in parallel)
            .setStartToCloseTimeout(Duration.ofDays(4)).setRetryOptions(DEFAULT_RETRY_OPTIONS).build());

    private static String prettifySqlQuery(String query) {
        query = query.replaceAll("\\s+", " ");
        query = query.trim();
        return query;
    }

    // Helper, not a workflow method
    private String moveTable(String srcTable, String srcIdColumn, int chunkSize, List<String> sqlQueries) {
        if (!srcTable.contains(".")) {
            throw new RuntimeException("Source table name must contain schema: " + srcTable);
        }
        if (!srcTable.startsWith("unsharded_")) {
            throw new RuntimeException("Source table name must start with 'unsharded_': " + srcTable);
        }
        if (srcIdColumn.contains(".")) {
            throw new RuntimeException("Invalid source ID column name: " + srcIdColumn);
        }

        boolean startIdMarkerFound = false;
        boolean endIdMarkerFound = false;

        for (String query : sqlQueries) {
            if (query.contains(START_ID_MARKER)) {
                startIdMarkerFound = true;
            }
            if (query.contains(END_ID_MARKER)) {
                endIdMarkerFound = true;
            }
        }

        if (!startIdMarkerFound) {
            throw new RuntimeException("SQL queries don't contain start ID marker '" + START_ID_MARKER + "': " + sqlQueries);
        }
        if (!endIdMarkerFound) {
            throw new RuntimeException("SQL queries don't contain end ID marker '" + END_ID_MARKER + "': " + sqlQueries);
        }

        Integer minId = this.minMaxTruncate.minColumnValue(srcTable, srcIdColumn);
        if (minId == null) {
            log.warn("Table '" + srcTable + "' seems to be empty.");
            return "";
        }

        Integer maxId = this.minMaxTruncate.maxColumnValue(srcTable, srcIdColumn);
        if (maxId == null) {
            log.warn("Table '" + srcTable + "' seems to be empty.");
            return "";
        }

        List<Promise<String>> promises = new ArrayList<>();

        for (int startId = minId; startId <= maxId; startId += chunkSize) {
            int endId = startId + chunkSize - 1;

            List<String> sqlQueriesWithIds = new ArrayList<>();

            for (String query : sqlQueries) {
                query = query.replace(START_ID_MARKER, String.valueOf(startId));
                query = query.replace(END_ID_MARKER, String.valueOf(endId));

                // Make queries look nicer in Temporal's log
                query = prettifySqlQuery(query);

                sqlQueriesWithIds.add(query);
            }

            promises.add(Async.function(moveRows::runQueriesInTransaction, sqlQueriesWithIds));
        }

        Promise.allOf(promises).get();

        this.minMaxTruncate.truncateIfEmpty(srcTable);

        // Don't know how to do Private<void>
        return "";
    }

    @Override
    public void moveRowsToShards() {

        this.moveTable(
                "unsharded_public.auth_user_request_daily_counts",
                "auth_user_request_daily_counts_id",
                // 338,454,970 rows in source table; 17 chunks
                20_000_000,
                List.of(String.format("""
                        WITH deleted_rows AS (
                            DELETE FROM unsharded_public.auth_user_request_daily_counts
                            WHERE auth_user_request_daily_counts_id BETWEEN %s AND %s
                            RETURNING
                                auth_user_request_daily_counts_id,
                                email,
                                day,
                                requests_count,
                                requested_items_count
                        )
                        INSERT INTO sharded_public.auth_user_request_daily_counts (
                            auth_user_request_daily_counts_id,
                            email,
                            day,
                            requests_count,
                            requested_items_count
                        )
                            SELECT
                                auth_user_request_daily_counts_id::BIGINT,
                                email,
                                day,
                                requests_count::BIGINT,
                                requested_items_count::BIGINT
                            FROM deleted_rows
                        ON CONFLICT (email, day) DO NOTHING
                            """, START_ID_MARKER, END_ID_MARKER))
        );

        this.moveTable(
                "unsharded_public.media_stats",
                "media_stats_id",
                // 89,970,140 in source table; 9 chunks
                10_000_000,
                List.of(String.format("""
                        WITH deleted_rows AS (
                            DELETE FROM unsharded_public.media_stats
                            WHERE media_stats_id BETWEEN %s AND %s
                            RETURNING
                                media_stats_id,
                                media_id,
                                num_stories,
                                num_sentences,
                                stat_date
                        )
                        INSERT INTO sharded_public.media_stats (
                            media_stats_id,
                            media_id,
                            num_stories,
                            num_sentences,
                            stat_date
                        )
                            SELECT
                                media_stats_id::BIGINT,
                                media_id::BIGINT,
                                num_stories::BIGINT,
                                num_sentences::BIGINT,
                                stat_date
                            FROM deleted_rows
                        ON CONFLICT (media_id, stat_date) DO NOTHING
                            """, START_ID_MARKER, END_ID_MARKER))
        );

        this.moveTable(
                "unsharded_public.media_coverage_gaps",
                "media_id",
                // MAX(media_id) = 1,892,933; 63,132,122 rows in source table; 10 chunks
                200_000,
                List.of(String.format("""
                        WITH deleted_rows AS (
                            DELETE FROM unsharded_public.media_coverage_gaps
                            WHERE media_id BETWEEN %s AND %s
                            RETURNING
                                media_id,
                                stat_week,
                                num_stories,
                                expected_stories,
                                num_sentences,
                                expected_sentences
                        )
                        INSERT INTO sharded_public.media_coverage_gaps (
                            media_id,
                            stat_week,
                            num_stories,
                            expected_stories,
                            num_sentences,
                            expected_sentences
                        )
                            SELECT
                                media_id::BIGINT,
                                stat_week,
                                num_stories,
                                expected_stories,
                                num_sentences,
                                expected_sentences
                            FROM deleted_rows
                            """, START_ID_MARKER, END_ID_MARKER))
        );

        this.moveTable(
                "unsharded_public.stories",
                "stories_id",
                // 2,119,319,121 in source table; 22 chunks
                100_000_000,
                List.of(String.format("""
                        WITH deleted_rows AS (
                            DELETE FROM unsharded_public.stories
                            WHERE stories_id BETWEEN %s AND %s
                            RETURNING
                                stories_id,
                                media_id,
                                url,
                                guid,
                                title,
                                normalized_title_hash,
                                description,
                                publish_date,
                                collect_date,
                                full_text_rss,
                                language
                        )
                        INSERT INTO sharded_public.stories (
                            stories_id,
                            media_id,
                            url,
                            guid,
                            title,
                            normalized_title_hash,
                            description,
                            publish_date,
                            collect_date,
                            full_text_rss,
                            language
                        )
                            SELECT
                                stories_id::BIGINT,
                                media_id::BIGINT,
                                url::TEXT,
                                guid::TEXT,
                                title,
                                normalized_title_hash,
                                description,
                                publish_date,
                                collect_date,
                                full_text_rss,
                                language
                            FROM deleted_rows
                                """, START_ID_MARKER, END_ID_MARKER))
        );

        this.moveTable(
                "unsharded_public.stories_ap_syndicated",
                "stories_ap_syndicated_id",
                // 1,715,725,719 in source table; 18 chunks
                100_000_000,
                List.of(String.format("""
                        WITH deleted_rows AS (
                            DELETE FROM unsharded_public.stories_ap_syndicated
                            WHERE stories_ap_syndicated_id BETWEEN %s AND %s
                            RETURNING
                                stories_ap_syndicated_id,
                                stories_id,
                                ap_syndicated
                        )
                        INSERT INTO sharded_public.stories_ap_syndicated (
                            stories_ap_syndicated_id,
                            stories_id,
                            ap_syndicated
                        )
                            SELECT
                                stories_ap_syndicated_id::BIGINT,
                                stories_id::BIGINT,
                                ap_syndicated
                            FROM deleted_rows
                        ON CONFLICT (stories_id) DO NOTHING
                        """, START_ID_MARKER, END_ID_MARKER))
        );

        this.moveTable(
                "unsharded_public.story_urls",
                "story_urls_id",
                // 2,223,082,697 in source table; 45 chunks
                50_000_000,
                List.of(String.format("""
                        WITH deleted_rows AS (
                            DELETE FROM unsharded_public.story_urls
                            WHERE story_urls_id BETWEEN %s and %s
                            RETURNING
                                story_urls_id,
                                stories_id,
                                url
                        )
                        INSERT INTO sharded_public.story_urls (
                            story_urls_id,
                            stories_id,
                            url
                        )
                            SELECT
                                story_urls_id::BIGINT,
                                stories_id::BIGINT,
                                url::TEXT
                            FROM deleted_rows
                        ON CONFLICT (url, stories_id) DO NOTHING
                            """, START_ID_MARKER, END_ID_MARKER))
        );

        final int storiesIdChunkSize = 100_000_000;

        Integer feedsStoriesMapMaxStoriesId = this.minMaxTruncate.maxColumnValue(
                "unsharded_public.feeds_stories_map",
                "stories_id"
        );
        if (feedsStoriesMapMaxStoriesId != null) {
            List<Promise<String>> feedsStoriesMapMovePromises = new ArrayList<>();
            List<Promise<String>> feedsStoriesMapTruncatePromises = new ArrayList<>();

            // FIXME off by one?
            for (int partitionIndex = 0; partitionIndex <= feedsStoriesMapMaxStoriesId / storiesIdChunkSize; ++partitionIndex) {
                feedsStoriesMapMovePromises.add(
                        Async.function(
                                moveRows::runQueriesInTransaction,
                                List.of(prettifySqlQuery(String.format("""
                                        WITH deleted_rows AS (
                                            DELETE FROM unsharded_public.feeds_stories_map_p_%02d
                                            RETURNING
                                                feeds_stories_map_p_id,
                                                feeds_id,
                                                stories_id
                                        )
                                        INSERT INTO sharded_public.feeds_stories_map (
                                            feeds_stories_map_id,
                                            feeds_id,
                                            stories_id
                                        )
                                            SELECT
                                                feeds_stories_map_p_id::BIGINT AS feeds_stories_map_id,
                                                feeds_id::BIGINT,
                                                stories_id::BIGINT
                                            FROM deleted_rows
                                        """, partitionIndex
                                )))
                        )
                );

                feedsStoriesMapTruncatePromises.add(
                        Async.function(
                                minMaxTruncate::truncateIfEmpty,
                                String.format("unsharded_public.feeds_stories_map_p_%02d", partitionIndex)
                        )
                );
            }

            Promise.allOf(feedsStoriesMapMovePromises).get();
            Promise.allOf(feedsStoriesMapTruncatePromises).get();
        }

        Integer storiesTagsMapMaxStoriesId = this.minMaxTruncate.maxColumnValue(
                "unsharded_public.stories_tags_map",
                "stories_id"
        );
        if (storiesTagsMapMaxStoriesId != null) {
            List<Promise<String>> storiesTagsMapMovePromises = new ArrayList<>();
            List<Promise<String>> storiesTagsMapTruncatePromises = new ArrayList<>();

            // FIXME off by one?
            for (int partitionIndex = 0; partitionIndex <= storiesTagsMapMaxStoriesId / storiesIdChunkSize; ++partitionIndex) {
                storiesTagsMapMovePromises.add(
                        Async.function(
                                moveRows::runQueriesInTransaction,
                                List.of(prettifySqlQuery(String.format("""
                                        WITH deleted_rows AS (
                                            DELETE FROM unsharded_public.stories_tags_map_p_%02d
                                            RETURNING
                                                stories_tags_map_p_id,
                                                stories_id,
                                                tags_id
                                        )
                                        INSERT INTO sharded_public.stories_tags_map (
                                            stories_tags_map_id,
                                            stories_id,
                                            tags_id
                                        )
                                            SELECT
                                                stories_tags_map_p_id::BIGINT AS stories_tags_map_id,
                                                stories_id::BIGINT,
                                                tags_id::BIGINT
                                            FROM deleted_rows
                                        """, partitionIndex
                                )))
                        )
                );

                storiesTagsMapTruncatePromises.add(
                        Async.function(
                                minMaxTruncate::truncateIfEmpty,
                                String.format("unsharded_public.stories_tags_map_p_%02d", partitionIndex)
                        )
                );
            }

            Promise.allOf(storiesTagsMapMovePromises).get();
            Promise.allOf(storiesTagsMapTruncatePromises).get();
        }

        Integer storySentencesMaxStoriesId = this.minMaxTruncate.maxColumnValue(
                "unsharded_public.story_sentences",
                "stories_id"
        );
        if (storySentencesMaxStoriesId != null) {
            List<Promise<String>> storySentencesMovePromises = new ArrayList<>();
            List<Promise<String>> storySentencesTruncatePromises = new ArrayList<>();

            // FIXME off by one?
            for (int partitionIndex = 0; partitionIndex <= storySentencesMaxStoriesId / storiesIdChunkSize; ++partitionIndex) {
                storySentencesMovePromises.add(
                        Async.function(
                                moveRows::runQueriesInTransaction,
                                List.of(prettifySqlQuery(String.format("""
                                        WITH deleted_rows AS (
                                            DELETE FROM unsharded_public.story_sentences_p_%02d
                                            RETURNING
                                                story_sentences_p_id,
                                                stories_id,
                                                sentence_number,
                                                sentence,
                                                media_id,
                                                publish_date,
                                                language,
                                                is_dup
                                        )
                                        INSERT INTO sharded_public.story_sentences (
                                            story_sentences_id,
                                            stories_id,
                                            sentence_number,
                                            sentence,
                                            media_id,
                                            publish_date,
                                            language,
                                            is_dup
                                        )
                                            SELECT
                                                story_sentences_p_id::BIGINT AS story_sentences_id,
                                                stories_id::BIGINT,
                                                sentence_number,
                                                sentence,
                                                media_id::BIGINT,
                                                publish_date,
                                                language,
                                                is_dup
                                            FROM deleted_rows
                                        """, partitionIndex
                                )))
                        )
                );

                storySentencesTruncatePromises.add(
                        Async.function(
                                minMaxTruncate::truncateIfEmpty,
                                String.format("unsharded_public.story_sentences_p_%02d", partitionIndex)
                        )
                );
            }

            Promise.allOf(storySentencesMovePromises).get();
            Promise.allOf(storySentencesTruncatePromises).get();
        }

        this.moveTable(
                "unsharded_public.solr_import_stories",
                "stories_id",
                // Rather small table, can copy everything in one go; 3 chunks
                1_000_000_000,
                List.of(String.format("""
                        WITH deleted_rows AS (
                            DELETE FROM unsharded_public.solr_import_stories
                            WHERE stories_id BETWEEN %s AND %s
                            RETURNING stories_id
                        )
                        INSERT INTO sharded_public.solr_import_stories (stories_id)
                            SELECT stories_id::BIGINT
                            FROM deleted_rows
                        ON CONFLICT (stories_id) DO NOTHING
                            """, START_ID_MARKER, END_ID_MARKER))
        );

        this.moveTable(
                "unsharded_public.solr_imported_stories",
                "stories_id",
                // MAX(stories_id) = 2,119,343,981; 11 chunks
                200_000_000,
                List.of(String.format("""
                        WITH deleted_rows AS (
                            DELETE FROM unsharded_public.solr_imported_stories
                            WHERE stories_id BETWEEN %s AND %s
                            RETURNING
                                stories_id,
                                import_date
                        )
                        INSERT INTO sharded_public.solr_imported_stories (
                            stories_id,
                            import_date
                        )
                            SELECT
                                stories_id::BIGINT,
                                import_date
                            FROM deleted_rows
                        ON CONFLICT (stories_id) DO NOTHING
                            """, START_ID_MARKER, END_ID_MARKER))
        );

        this.moveTable(
                "unsharded_public.topic_merged_stories_map",
                "source_stories_id",
                // Rather small table, can copy everything on one go; 3 chunks
                1_000_000_000,
                List.of(String.format("""
                        WITH deleted_rows AS (
                            DELETE FROM unsharded_public.topic_merged_stories_map
                            WHERE source_stories_id BETWEEN %s AND %s
                            RETURNING
                                source_stories_id,
                                target_stories_id
                        )
                        INSERT INTO sharded_public.topic_merged_stories_map (
                            source_stories_id,
                            target_stories_id
                        )
                            SELECT
                                source_stories_id::BIGINT,
                                target_stories_id::BIGINT
                            FROM deleted_rows
                        ON CONFLICT (source_stories_id, target_stories_id) DO NOTHING
                            """, START_ID_MARKER, END_ID_MARKER))
        );

        this.moveTable(
                "unsharded_public.story_statistics",
                "story_statistics_id",
                // Rather small table, can copy everything on one go; 3 chunks
                1_000_000_000,
                List.of(String.format("""
                        WITH deleted_rows AS (
                            DELETE FROM unsharded_public.story_statistics
                            WHERE story_statistics_id BETWEEN %s AND %s
                            RETURNING
                                story_statistics_id,
                                stories_id,
                                facebook_share_count,
                                facebook_comment_count,
                                facebook_reaction_count,
                                facebook_api_collect_date,
                                facebook_api_error
                        )
                        INSERT INTO sharded_public.story_statistics (
                            story_statistics_id,
                            stories_id,
                            facebook_share_count,
                            facebook_comment_count,
                            facebook_reaction_count,
                            facebook_api_collect_date,
                            facebook_api_error
                        )
                            SELECT
                                story_statistics_id::BIGINT,
                                stories_id::BIGINT,
                                facebook_share_count::BIGINT,
                                facebook_comment_count::BIGINT,
                                facebook_reaction_count::BIGINT,
                                facebook_api_collect_date,
                                facebook_api_error
                            FROM deleted_rows
                            """, START_ID_MARKER, END_ID_MARKER))
        );

        this.moveTable(
                "unsharded_public.processed_stories",
                "processed_stories_id",
                // 2,518,182,153 in source table; 13 chunks
                200_000_000,
                List.of(String.format("""
                        WITH deleted_rows AS (
                            DELETE FROM unsharded_public.processed_stories
                            WHERE processed_stories_id BETWEEN %s AND %s
                            RETURNING
                                processed_stories_id,
                                stories_id
                        )
                        INSERT INTO sharded_public.processed_stories (
                            processed_stories_id,
                            stories_id
                        )
                            SELECT
                                processed_stories_id::BIGINT,
                                stories_id::BIGINT
                            FROM deleted_rows
                        ON CONFLICT (stories_id) DO NOTHING
                            """, START_ID_MARKER, END_ID_MARKER))
        );

        this.moveTable(
                "unsharded_public.scraped_stories",
                "scraped_stories_id",
                // Rather small table, can copy everything on one go; 3 chunks
                1_000_000_000,
                List.of(String.format("""
                        WITH deleted_rows AS (
                            DELETE FROM unsharded_public.scraped_stories
                            WHERE scraped_stories_id BETWEEN %s AND %s
                            RETURNING
                                scraped_stories_id,
                                stories_id,
                                import_module
                        )
                        INSERT INTO sharded_public.scraped_stories (
                            scraped_stories_id,
                            stories_id,
                            import_module
                        )
                            SELECT
                                scraped_stories_id::BIGINT,
                                stories_id::BIGINT,
                                import_module
                            FROM deleted_rows
                            """, START_ID_MARKER, END_ID_MARKER))
        );

        this.moveTable(
                "unsharded_public.story_enclosures",
                "story_enclosures_id",
                // 153,858,997 in source table; 16 chunks
                10_000_000,
                List.of(String.format("""
                        WITH deleted_rows AS (
                            DELETE FROM unsharded_public.story_enclosures
                            WHERE story_enclosures_id BETWEEN %s AND %s
                            RETURNING
                                story_enclosures_id,
                                stories_id,
                                url,
                                mime_type,
                                length
                        )
                        INSERT INTO sharded_public.story_enclosures (
                            story_enclosures_id,
                            stories_id,
                            url,
                            mime_type,
                            length
                        )
                            SELECT
                                story_enclosures_id::BIGINT,
                                stories_id::BIGINT,
                                url,
                                mime_type,
                                length
                            FROM deleted_rows
                            """, START_ID_MARKER, END_ID_MARKER))
        );

        final String downloadsIdSrcColumns = """
                downloads_id,
                feeds_id,
                stories_id,
                parent,
                url,
                host,
                download_time,
                type,
                state,
                path,
                error_message,
                priority,
                sequence,
                extracted
                    """;
        final String downloadsIdDstColumns = """
                downloads_id::BIGINT,
                feeds_id::BIGINT,
                stories_id::BIGINT,
                parent,
                url,
                host,
                download_time,
                type::TEXT::public.download_type,
                state::TEXT::public.download_state,
                path,
                error_message,
                priority,
                sequence,
                extracted
                    """;

        this.moveTable(
                "unsharded_public.downloads_error",
                "downloads_id",
                // 114,330,304 in source table; 12 chunks
                10_000_000,
                List.of(String.format("""
                        WITH deleted_rows AS (
                            DELETE FROM unsharded_public.downloads_error
                            WHERE downloads_id BETWEEN %1$s AND %2$s
                            RETURNING %3$s
                        )
                        INSERT INTO sharded_public.downloads_error (%3$s)
                            SELECT %4$s
                            FROM deleted_rows
                            """, START_ID_MARKER, END_ID_MARKER, downloadsIdSrcColumns, downloadsIdDstColumns))
        );

        final int downloadsIdChunkSize = 100_000_000;

        Integer downloadsSuccessContentMaxDownloadsId = this.minMaxTruncate.maxColumnValue(
                "unsharded_public.downloads_success_content",
                "downloads_id"
        );
        if (downloadsSuccessContentMaxDownloadsId != null) {
            List<Promise<String>> downloadsSuccessContentMovePromises = new ArrayList<>();
            List<Promise<String>> downloadsSuccessContentTruncatePromises = new ArrayList<>();

            // FIXME off by one?
            for (int partitionIndex = 0; partitionIndex <= downloadsSuccessContentMaxDownloadsId / downloadsIdChunkSize; ++partitionIndex) {
                downloadsSuccessContentMovePromises.add(
                        Async.function(
                                moveRows::runQueriesInTransaction,
                                List.of(prettifySqlQuery(String.format("""
                                        WITH deleted_rows AS (
                                            DELETE FROM unsharded_public.downloads_success_content_%1$02d
                                            RETURNING %2$s
                                        )
                                        INSERT INTO sharded_public.downloads_success (%2$s)
                                            SELECT %3$s
                                            FROM deleted_rows
                                            """, partitionIndex, downloadsIdSrcColumns, downloadsIdDstColumns)))
                        )
                );

                downloadsSuccessContentTruncatePromises.add(
                        Async.function(
                                minMaxTruncate::truncateIfEmpty,
                                String.format("unsharded_public.downloads_success_content_%02d", partitionIndex)
                        )
                );
            }

            Promise.allOf(downloadsSuccessContentMovePromises).get();
            Promise.allOf(downloadsSuccessContentTruncatePromises).get();
        }

        Integer downloadsSuccessFeedMaxDownloadsId = this.minMaxTruncate.maxColumnValue(
                "unsharded_public.downloads_success_feed",
                "downloads_id"
        );
        if (downloadsSuccessFeedMaxDownloadsId != null) {
            List<Promise<String>> downloadsSuccessFeedMovePromises = new ArrayList<>();
            List<Promise<String>> downloadsSuccessFeedTruncatePromises = new ArrayList<>();

            // FIXME off by one?
            for (int partitionIndex = 0; partitionIndex <= downloadsSuccessFeedMaxDownloadsId / downloadsIdChunkSize; ++partitionIndex) {
                downloadsSuccessFeedMovePromises.add(
                        Async.function(
                                moveRows::runQueriesInTransaction,
                                List.of(prettifySqlQuery(String.format("""
                                        WITH deleted_rows AS (
                                            DELETE FROM unsharded_public.downloads_success_feed_%1$02d
                                            RETURNING %2$s
                                        )
                                        INSERT INTO sharded_public.downloads_success (%2$s)
                                            SELECT %3$s
                                            FROM deleted_rows
                                            """, partitionIndex, downloadsIdSrcColumns, downloadsIdDstColumns)))
                        )
                );

                downloadsSuccessFeedTruncatePromises.add(
                        Async.function(
                                minMaxTruncate::truncateIfEmpty,
                                String.format("unsharded_public.downloads_success_feed_%02d", partitionIndex)
                        )
                );
            }

            Promise.allOf(downloadsSuccessFeedMovePromises).get();
            Promise.allOf(downloadsSuccessFeedTruncatePromises).get();
        }

        Integer downloadTextsMaxDownloadsId = this.minMaxTruncate.maxColumnValue(
                "unsharded_public.download_texts",
                "downloads_id"
        );
        if (downloadTextsMaxDownloadsId != null) {
            List<Promise<String>> downloadTextsMovePromises = new ArrayList<>();
            List<Promise<String>> downloadTextsTruncatePromises = new ArrayList<>();

            // FIXME off by one?
            for (int partitionIndex = 0; partitionIndex <= downloadTextsMaxDownloadsId / downloadsIdChunkSize; ++partitionIndex) {
                downloadTextsMovePromises.add(
                        Async.function(
                                moveRows::runQueriesInTransaction,
                                List.of(prettifySqlQuery(String.format("""
                                        WITH deleted_rows AS (
                                            DELETE FROM unsharded_public.download_texts_%02d
                                            RETURNING
                                                download_texts_id,
                                                downloads_id,
                                                download_text,
                                                download_text_length
                                        )
                                        INSERT INTO sharded_public.download_texts (
                                            download_texts_id,
                                            downloads_id,
                                            download_text,
                                            download_text_length
                                        )
                                            SELECT
                                                download_texts_id,
                                                downloads_id,
                                                download_text,
                                                download_text_length
                                            FROM deleted_rows
                                            """, partitionIndex)))
                        )
                );

                downloadTextsTruncatePromises.add(
                        Async.function(
                                minMaxTruncate::truncateIfEmpty,
                                String.format("unsharded_public.download_texts_%02d", partitionIndex)
                        )
                );
            }

            Promise.allOf(downloadTextsMovePromises).get();
            Promise.allOf(downloadTextsTruncatePromises).get();
        }

        this.moveTable(
                "unsharded_public.topic_stories",
                "topic_stories_id",
                // 165,026,730 in source table; 9 chunks
                20_000_000,
                List.of(String.format("""
                        WITH deleted_rows AS (
                            DELETE FROM unsharded_public.topic_stories
                            WHERE topic_stories_id BETWEEN %s AND %s
                            RETURNING
                                topic_stories_id,
                                topics_id,
                                stories_id,
                                link_mined,
                                iteration,
                                link_weight,
                                redirect_url,
                                valid_foreign_rss_story,
                                link_mine_error
                        )
                        INSERT INTO sharded_public.topic_stories (
                            topic_stories_id,
                            topics_id,
                            stories_id,
                            link_mined,
                            iteration,
                            link_weight,
                            redirect_url,
                            valid_foreign_rss_story,
                            link_mine_error
                        )
                            SELECT
                                topic_stories_id::BIGINT,
                                topics_id::BIGINT,
                                stories_id::BIGINT,
                                link_mined,
                                iteration::BIGINT,
                                link_weight,
                                redirect_url,
                                valid_foreign_rss_story,
                                link_mine_error
                            FROM deleted_rows
                            """, START_ID_MARKER, END_ID_MARKER))
        );

        this.moveTable(
                "unsharded_public.topic_links",
                "topic_links_id",
                // 1,433,314,412 in source table; 15 chunks
                100_000_000,
                List.of(String.format("""
                        WITH deleted_rows AS (
                            DELETE FROM unsharded_public.topic_links
                            WHERE topic_links_id BETWEEN %s AND %s
                            RETURNING
                                topic_links_id,
                                topics_id,
                                stories_id,
                                url,
                                redirect_url,
                                ref_stories_id,
                                link_spidered
                        )
                        INSERT INTO sharded_public.topic_links (
                            topic_links_id,
                            topics_id,
                            stories_id,
                            url,
                            redirect_url,
                            ref_stories_id,
                            link_spidered
                        )
                            SELECT
                                topic_links_id::BIGINT,
                                topics_id::BIGINT,
                                stories_id::BIGINT,
                                url,
                                redirect_url,
                                ref_stories_id::BIGINT,
                                link_spidered
                            FROM deleted_rows
                            """, START_ID_MARKER, END_ID_MARKER))
        );

        this.moveTable(
                "unsharded_public.topic_fetch_urls",
                "topic_fetch_urls_id",
                // 705,821,290 in source table; 8 chunks
                100_000_000,
                List.of(String.format("""
                        WITH deleted_rows AS (
                            DELETE FROM unsharded_public.topic_fetch_urls
                            WHERE topic_fetch_urls_id BETWEEN %s AND %s
                            RETURNING
                                topic_fetch_urls_id,
                                topics_id,
                                url,
                                code,
                                fetch_date,
                                state,
                                message,
                                stories_id,
                                assume_match,
                                topic_links_id
                        )
                        INSERT INTO sharded_public.topic_fetch_urls (
                            topic_fetch_urls_id,
                            topics_id,
                            url,
                            code,
                            fetch_date,
                            state,
                            message,
                            stories_id,
                            assume_match,
                            topic_links_id
                        )
                            SELECT
                                topic_fetch_urls_id::BIGINT,
                                topics_id::BIGINT,
                                url,
                                code,
                                fetch_date,
                                state,
                                message,
                                stories_id::BIGINT,
                                assume_match,
                                topic_links_id::BIGINT
                            FROM deleted_rows
                            """, START_ID_MARKER, END_ID_MARKER))
        );

        this.moveTable(
                "unsharded_public.topic_posts",
                "topic_posts_id",
                // 95,486,494 in source table; 10 chunks
                10_000_000,
                Arrays.asList(
                        // Citus doesn't like it when we join local (unsharded) and distributed tables in this case
                        // therefore we create a temporary table first
                        String.format("""
                                CREATE TEMPORARY TABLE temp_chunk_topic_post_days AS
                                    SELECT
                                        topic_post_days_id::INT,
                                        topics_id::INT
                                    FROM public.topic_post_days
                                    WHERE topic_post_days_id IN (
                                        SELECT topic_post_days_id
                                        FROM unsharded_public.topic_posts
                                        WHERE topic_posts_id BETWEEN %s AND %s
                                    )
                                        """, START_ID_MARKER, END_ID_MARKER),
                        String.format("""
                                WITH deleted_rows AS (
                                    DELETE FROM unsharded_public.topic_posts
                                    USING temp_chunk_topic_post_days
                                    WHERE
                                        unsharded_public.topic_posts.topic_post_days_id
                                            = temp_chunk_topic_post_days.topic_post_days_id AND
                                        unsharded_public.topic_posts.topic_posts_id BETWEEN %s AND %s
                                    RETURNING
                                        unsharded_public.topic_posts.topic_posts_id,
                                        temp_chunk_topic_post_days.topics_id,
                                        unsharded_public.topic_posts.topic_post_days_id,
                                        unsharded_public.topic_posts.data,
                                        unsharded_public.topic_posts.post_id,
                                        unsharded_public.topic_posts.content,
                                        unsharded_public.topic_posts.publish_date,
                                        unsharded_public.topic_posts.author,
                                        unsharded_public.topic_posts.channel,
                                        unsharded_public.topic_posts.url
                                )
                                INSERT INTO sharded_public.topic_posts (
                                    topic_posts_id,
                                    topics_id,
                                    topic_post_days_id,
                                    data,
                                    post_id,
                                    content,
                                    publish_date,
                                    author,
                                    channel,
                                    url
                                )
                                    SELECT
                                        topic_posts_id::BIGINT,
                                        topics_id::BIGINT,
                                        topic_post_days_id::BIGINT,
                                        data,
                                        post_id::TEXT,
                                        content,
                                        publish_date,
                                        author::TEXT,
                                        channel::TEXT,
                                        url
                                    FROM deleted_rows
                                            """, START_ID_MARKER, END_ID_MARKER),
                        "TRUNCATE temp_chunk_topic_post_days",
                        "DROP TABLE temp_chunk_topic_post_days"
                )
        );

        this.moveTable(
                "unsharded_public.topic_post_urls",
                "topic_post_urls_id",
                // 50,726,436 in source table; 11 chunks
                5_000_000,
                Arrays.asList(
                        // Citus doesn't like it when we join local (unsharded) and distributed tables in this case
                        // therefore we create a temporary table first
                        String.format("""
                                CREATE TEMPORARY TABLE temp_chunk_topic_posts AS
                                    SELECT
                                        topic_posts_id::INT,
                                        topics_id::INT
                                    FROM sharded_public.topic_posts
                                    WHERE topic_posts_id IN (
                                        SELECT topic_posts_id
                                        FROM unsharded_public.topic_post_urls
                                        WHERE topic_post_urls_id BETWEEN %s AND %s
                                    )
                                """, START_ID_MARKER, END_ID_MARKER),
                        String.format("""
                                WITH deleted_rows AS (
                                    DELETE FROM unsharded_public.topic_post_urls
                                    USING temp_chunk_topic_posts
                                    WHERE
                                        unsharded_public.topic_post_urls.topic_posts_id
                                            = temp_chunk_topic_posts.topic_posts_id AND
                                        unsharded_public.topic_post_urls.topic_post_urls_id BETWEEN %s AND %s
                                    RETURNING
                                        unsharded_public.topic_post_urls.topic_post_urls_id,
                                        temp_chunk_topic_posts.topics_id,
                                        unsharded_public.topic_post_urls.topic_posts_id,
                                        unsharded_public.topic_post_urls.url
                                )
                                INSERT INTO sharded_public.topic_post_urls (
                                    topic_post_urls_id,
                                    topics_id,
                                    topic_posts_id,
                                    url
                                )
                                    SELECT
                                        topic_post_urls_id::BIGINT,
                                        topics_id,
                                        topic_posts_id::BIGINT,
                                        url::TEXT
                                    FROM deleted_rows
                                """, START_ID_MARKER, END_ID_MARKER),
                        "TRUNCATE temp_chunk_topic_posts",
                        "DROP TABLE temp_chunk_topic_posts"
                )
        );

        this.moveTable(
                "unsharded_public.topic_seed_urls",
                "topic_seed_urls_id",
                // 499,926,808 in source table; 50 chunks
                10_000_000,
                List.of(String.format("""
                        WITH deleted_rows AS (
                            DELETE FROM unsharded_public.topic_seed_urls
                            WHERE topic_seed_urls_id BETWEEN %s AND %s
                            RETURNING
                                topic_seed_urls_id,
                                topics_id,
                                url,
                                source,
                                stories_id,
                                processed,
                                assume_match,
                                content,
                                guid,
                                title,
                                publish_date,
                                topic_seed_queries_id,
                                topic_post_urls_id
                        )
                        INSERT INTO sharded_public.topic_seed_urls (
                            topic_seed_urls_id,
                            topics_id,
                            url,
                            source,
                            stories_id,
                            processed,
                            assume_match,
                            content,
                            guid,
                            title,
                            publish_date,
                            topic_seed_queries_id,
                            topic_post_urls_id
                        )
                            SELECT
                                topic_seed_urls_id::BIGINT,
                                topics_id::BIGINT,
                                url,
                                source,
                                stories_id::BIGINT,
                                processed,
                                assume_match,
                                content,
                                guid,
                                title,
                                publish_date,
                                topic_seed_queries_id::BIGINT,
                                topic_post_urls_id::BIGINT
                            FROM deleted_rows
                        """, START_ID_MARKER, END_ID_MARKER))
        );

        this.moveTable(
                "unsharded_snap.stories",
                "snapshots_id",
                // MAX(snapshots_id) = 7690 in source table; 8 chunks
                1000,
                Arrays.asList(
                        // Citus doesn't like it when we join local (unsharded) and distributed tables in this case
                        // therefore we create a temporary table first
                        String.format("""
                                CREATE TEMPORARY TABLE temp_chunk_snapshots AS
                                    SELECT
                                        snapshots_id::INT,
                                        topics_id::INT
                                    FROM public.snapshots
                                    WHERE snapshots_id BETWEEN %s AND %s
                                """, START_ID_MARKER, END_ID_MARKER),

                        // snap.stories (topics_id, snapshots_id, stories_id, media_id, guid) also has a unique index,
                        // and PostgreSQL doesn't support multiple ON CONFLICT, so let's hope that there are no
                        // duplicates in the source table
                        String.format("""
                                WITH deleted_rows AS (
                                    DELETE FROM unsharded_snap.stories
                                    USING temp_chunk_snapshots
                                    WHERE
                                        unsharded_snap.stories.snapshots_id
                                            = temp_chunk_snapshots.snapshots_id AND
                                        unsharded_snap.stories.snapshots_id BETWEEN %s AND %s
                                    RETURNING
                                        temp_chunk_snapshots.topics_id,
                                        unsharded_snap.stories.snapshots_id,
                                        unsharded_snap.stories.stories_id,
                                        unsharded_snap.stories.media_id,
                                        unsharded_snap.stories.url,
                                        unsharded_snap.stories.guid,
                                        unsharded_snap.stories.title,
                                        unsharded_snap.stories.publish_date,
                                        unsharded_snap.stories.collect_date,
                                        unsharded_snap.stories.full_text_rss,
                                        unsharded_snap.stories.language
                                )
                                INSERT INTO sharded_snap.stories (
                                    topics_id,
                                    snapshots_id,
                                    stories_id,
                                    media_id,
                                    url,
                                    guid,
                                    title,
                                    publish_date,
                                    collect_date,
                                    full_text_rss,
                                    language
                                )
                                    SELECT
                                        topics_id::BIGINT,
                                        snapshots_id::BIGINT,
                                        stories_id::BIGINT,
                                        media_id::BIGINT,
                                        url::TEXT,
                                        guid::TEXT,
                                        title,
                                        publish_date,
                                        collect_date,
                                        full_text_rss,
                                        language
                                    FROM deleted_rows
                                ON CONFLICT (topics_id, snapshots_id, stories_id) DO NOTHING
                                """, START_ID_MARKER, END_ID_MARKER),
                        "TRUNCATE temp_chunk_snapshots",
                        "DROP TABLE temp_chunk_snapshots"
                )
        );

        this.moveTable(
                "unsharded_snap.topic_stories",
                "snapshots_id",
                // MAX(snapshots_id) = 7690 in source table; 8 chunks
                1000,
                List.of(String.format("""
                        WITH deleted_rows AS (
                            DELETE FROM unsharded_snap.topic_stories
                            WHERE snapshots_id BETWEEN %s AND %s
                            RETURNING
                                topics_id,
                                snapshots_id,
                                topic_stories_id,
                                stories_id,
                                link_mined,
                                iteration,
                                link_weight,
                                redirect_url,
                                valid_foreign_rss_story
                        )
                        INSERT INTO sharded_snap.topic_stories (
                            topics_id,
                            snapshots_id,
                            topic_stories_id,
                            stories_id,
                            link_mined,
                            iteration,
                            link_weight,
                            redirect_url,
                            valid_foreign_rss_story
                        )
                            SELECT
                                topics_id::BIGINT,
                                snapshots_id::BIGINT,
                                topic_stories_id::BIGINT,
                                stories_id::BIGINT,
                                link_mined,
                                iteration::BIGINT,
                                link_weight,
                                redirect_url,
                                valid_foreign_rss_story
                            FROM deleted_rows
                        ON CONFLICT (topics_id, snapshots_id, stories_id) DO NOTHING
                            """, START_ID_MARKER, END_ID_MARKER))
        );

        this.moveTable(
                "unsharded_snap.topic_links_cross_media",
                "snapshots_id",
                // MAX(snapshots_id) = 7690 in source table; 8 chunks
                1000,
                List.of(String.format("""
                        WITH deleted_rows AS (
                            DELETE FROM unsharded_snap.topic_links_cross_media
                            WHERE snapshots_id BETWEEN %s AND %s
                            RETURNING
                                topics_id,
                                snapshots_id,
                                topic_links_id,
                                stories_id,
                                url,
                                ref_stories_id
                        )
                        INSERT INTO sharded_snap.topic_links_cross_media (
                            topics_id,
                            snapshots_id,
                            topic_links_id,
                            stories_id,
                            url,
                            ref_stories_id
                        )
                            SELECT
                                topics_id::BIGINT,
                                snapshots_id::BIGINT,
                                topic_links_id::BIGINT,
                                stories_id::BIGINT,
                                url,
                                ref_stories_id::BIGINT
                            FROM deleted_rows
                        ON CONFLICT (topics_id, snapshots_id, stories_id, ref_stories_id) DO NOTHING
                            """, START_ID_MARKER, END_ID_MARKER))
        );

        this.moveTable(
                "unsharded_snap.media",
                "snapshots_id",
                // MAX(snapshots_id) = 7690 in source table; 8 chunks
                1000,
                Arrays.asList(
                        // Citus doesn't like it when we join local (unsharded) and distributed tables in this case
                        // therefore we create a temporary table first
                        String.format("""
                                CREATE TEMPORARY TABLE temp_chunk_snapshots AS
                                    SELECT
                                        snapshots_id::INT,
                                        topics_id::INT
                                    FROM public.snapshots
                                    WHERE snapshots_id BETWEEN %s AND %s
                                    """, START_ID_MARKER, END_ID_MARKER),
                        String.format("""
                                WITH deleted_rows AS (
                                    DELETE FROM unsharded_snap.media
                                    USING temp_chunk_snapshots
                                    WHERE
                                        unsharded_snap.media.snapshots_id = temp_chunk_snapshots.snapshots_id AND
                                        unsharded_snap.media.snapshots_id BETWEEN %s AND %s
                                    RETURNING
                                        temp_chunk_snapshots.topics_id,
                                        unsharded_snap.media.snapshots_id,
                                        unsharded_snap.media.media_id,
                                        unsharded_snap.media.url,
                                        unsharded_snap.media.name,
                                        unsharded_snap.media.full_text_rss,
                                        unsharded_snap.media.foreign_rss_links,
                                        unsharded_snap.media.dup_media_id,
                                        unsharded_snap.media.is_not_dup
                                )
                                INSERT INTO sharded_snap.media (
                                    topics_id,
                                    snapshots_id,
                                    media_id,
                                    url,
                                    name,
                                    full_text_rss,
                                    foreign_rss_links,
                                    dup_media_id,
                                    is_not_dup
                                )
                                    SELECT
                                        topics_id::BIGINT,
                                        snapshots_id::BIGINT,
                                        media_id::BIGINT,
                                        url::TEXT,
                                        name::TEXT,
                                        full_text_rss,
                                        foreign_rss_links,
                                        dup_media_id::BIGINT,
                                        is_not_dup
                                    FROM deleted_rows
                                ON CONFLICT (topics_id, snapshots_id, media_id) DO NOTHING
                                            """, START_ID_MARKER, END_ID_MARKER),
                        "TRUNCATE temp_chunk_snapshots",
                        "DROP TABLE temp_chunk_snapshots"
                )
        );

        this.moveTable(
                "unsharded_snap.media_tags_map",
                "snapshots_id",
                // MAX(snapshots_id) = 7690 in source table; 8 chunks
                1000,
                Arrays.asList(
                        // Citus doesn't like it when we join local (unsharded) and distributed tables in this case
                        // therefore we create a temporary table first
                        String.format("""
                                CREATE TEMPORARY TABLE temp_chunk_snapshots AS
                                    SELECT
                                        snapshots_id::INT,
                                        topics_id::INT
                                    FROM public.snapshots
                                    WHERE snapshots_id BETWEEN %s AND %s
                                    """, START_ID_MARKER, END_ID_MARKER),
                        String.format("""
                                WITH deleted_rows AS (
                                    DELETE FROM unsharded_snap.media_tags_map
                                    USING temp_chunk_snapshots
                                    WHERE
                                        unsharded_snap.media_tags_map.snapshots_id = temp_chunk_snapshots.snapshots_id AND
                                        unsharded_snap.media_tags_map.snapshots_id BETWEEN %s AND %s
                                    RETURNING
                                        temp_chunk_snapshots.topics_id,
                                        unsharded_snap.media_tags_map.snapshots_id,
                                        unsharded_snap.media_tags_map.media_tags_map_id,
                                        unsharded_snap.media_tags_map.media_id,
                                        unsharded_snap.media_tags_map.tags_id
                                )
                                INSERT INTO sharded_snap.media_tags_map (
                                    topics_id,
                                    snapshots_id,
                                    media_tags_map_id,
                                    media_id,
                                    tags_id
                                )
                                    SELECT
                                        topics_id::BIGINT,
                                        snapshots_id::BIGINT,
                                        media_tags_map_id::BIGINT,
                                        media_id::BIGINT,
                                        tags_id::BIGINT
                                    FROM deleted_rows
                                ON CONFLICT (topics_id, snapshots_id, media_id, tags_id) DO NOTHING
                                    """, START_ID_MARKER, END_ID_MARKER),
                        "TRUNCATE temp_chunk_snapshots",
                        "DROP TABLE temp_chunk_snapshots"
                )
        );

        this.moveTable(
                "unsharded_snap.stories_tags_map",
                "snapshots_id",
                // MAX(snapshots_id) = 7690 in source table; 8 chunks
                1000,
                Arrays.asList(
                        // Citus doesn't like it when we join local (unsharded) and distributed tables in this case
                        // therefore we create a temporary table first
                        String.format("""
                                CREATE TEMPORARY TABLE temp_chunk_snapshots AS
                                    SELECT
                                        snapshots_id::INT,
                                        topics_id::INT
                                    FROM public.snapshots
                                    WHERE snapshots_id BETWEEN %s AND %s
                                    """, START_ID_MARKER, END_ID_MARKER),
                        String.format("""
                                WITH deleted_rows AS (
                                    DELETE FROM unsharded_snap.stories_tags_map
                                    USING temp_chunk_snapshots
                                    WHERE
                                        unsharded_snap.stories_tags_map.snapshots_id
                                            = temp_chunk_snapshots.snapshots_id AND
                                        unsharded_snap.stories_tags_map.snapshots_id BETWEEN %s AND %s
                                    RETURNING
                                        temp_chunk_snapshots.topics_id,
                                        unsharded_snap.stories_tags_map.snapshots_id,
                                        unsharded_snap.stories_tags_map.stories_tags_map_id,
                                        unsharded_snap.stories_tags_map.stories_id,
                                        unsharded_snap.stories_tags_map.tags_id
                                )
                                INSERT INTO sharded_snap.stories_tags_map (
                                    topics_id,
                                    snapshots_id,
                                    stories_tags_map_id,
                                    stories_id,
                                    tags_id
                                )
                                    SELECT
                                        topics_id::BIGINT,
                                        snapshots_id::BIGINT,
                                        stories_tags_map_id::BIGINT,
                                        stories_id::BIGINT,
                                        tags_id::BIGINT
                                    FROM deleted_rows
                                ON CONFLICT (topics_id, snapshots_id, stories_id, tags_id) DO NOTHING
                                    """, START_ID_MARKER, END_ID_MARKER),
                        "TRUNCATE temp_chunk_snapshots",
                        "DROP TABLE temp_chunk_snapshots"
                )
        );

        this.moveTable(
                "unsharded_snap.story_links",
                "timespans_id",
                // MAX(timespans_id) = 1_362_209 in source table; 10 chunks
                150_000,
                Arrays.asList(
                        // Citus doesn't like it when we join local (unsharded) and distributed tables in this case
                        // therefore we create a temporary table first
                        String.format("""
                                CREATE TEMPORARY TABLE temp_chunk_timespans AS
                                    SELECT
                                        timespans_id::INT,
                                        topics_id::INT
                                    FROM public.timespans
                                    WHERE timespans_id BETWEEN %s AND %s
                                        """, START_ID_MARKER, END_ID_MARKER),
                        String.format("""
                                WITH deleted_rows AS (
                                    DELETE FROM unsharded_snap.story_links
                                    USING temp_chunk_timespans
                                    WHERE
                                        unsharded_snap.story_links.timespans_id = temp_chunk_timespans.timespans_id AND
                                        unsharded_snap.story_links.timespans_id BETWEEN %s AND %s
                                    RETURNING
                                        temp_chunk_timespans.topics_id,
                                        unsharded_snap.story_links.timespans_id,
                                        unsharded_snap.story_links.source_stories_id,
                                        unsharded_snap.story_links.ref_stories_id
                                )
                                INSERT INTO sharded_snap.story_links (
                                    topics_id,
                                    timespans_id,
                                    source_stories_id,
                                    ref_stories_id
                                )
                                    SELECT
                                        topics_id::BIGINT,
                                        timespans_id::BIGINT,
                                        source_stories_id::BIGINT,
                                        ref_stories_id::BIGINT
                                    FROM deleted_rows
                                ON CONFLICT (topics_id, timespans_id, source_stories_id, ref_stories_id) DO NOTHING
                                    """, START_ID_MARKER, END_ID_MARKER),
                        "TRUNCATE temp_chunk_timespans",
                        "DROP TABLE temp_chunk_timespans"
                )
        );

        this.moveTable(
                "unsharded_snap.story_link_counts",
                "timespans_id",
                // MAX(timespans_id) = 1_362_209 in source table; 10 chunks
                150_000,
                Arrays.asList(
                        String.format("""
                                CREATE TEMPORARY TABLE temp_chunk_timespans AS
                                    SELECT
                                        timespans_id::INT,
                                        topics_id::INT
                                    FROM public.timespans
                                    WHERE timespans_id BETWEEN %s AND %s
                                        """, START_ID_MARKER, END_ID_MARKER),
                        String.format("""
                                WITH deleted_rows AS (
                                    DELETE FROM unsharded_snap.story_link_counts
                                    USING temp_chunk_timespans
                                    WHERE
                                        unsharded_snap.story_link_counts.timespans_id = temp_chunk_timespans.timespans_id AND
                                        unsharded_snap.story_link_counts.timespans_id BETWEEN %s AND %s
                                    RETURNING
                                        temp_chunk_timespans.topics_id,
                                        unsharded_snap.story_link_counts.timespans_id,
                                        unsharded_snap.story_link_counts.stories_id,
                                        unsharded_snap.story_link_counts.media_inlink_count,
                                        unsharded_snap.story_link_counts.inlink_count,
                                        unsharded_snap.story_link_counts.outlink_count,
                                        unsharded_snap.story_link_counts.facebook_share_count,
                                        unsharded_snap.story_link_counts.post_count,
                                        unsharded_snap.story_link_counts.author_count,
                                        unsharded_snap.story_link_counts.channel_count
                                )
                                INSERT INTO sharded_snap.story_link_counts (
                                    topics_id,
                                    timespans_id,
                                    stories_id,
                                    media_inlink_count,
                                    inlink_count,
                                    outlink_count,
                                    facebook_share_count,
                                    post_count,
                                    author_count,
                                    channel_count
                                )
                                    SELECT
                                        topics_id::BIGINT,
                                        timespans_id::BIGINT,
                                        stories_id::BIGINT,
                                        media_inlink_count::BIGINT,
                                        inlink_count::BIGINT,
                                        outlink_count::BIGINT,
                                        facebook_share_count::BIGINT,
                                        post_count::BIGINT,
                                        author_count::BIGINT,
                                        channel_count::BIGINT
                                    FROM deleted_rows
                                ON CONFLICT (topics_id, timespans_id, stories_id) DO NOTHING
                                    """, START_ID_MARKER, END_ID_MARKER),
                        "TRUNCATE temp_chunk_timespans",
                        "DROP TABLE temp_chunk_timespans"
                )
        );

        this.moveTable(
                "unsharded_snap.medium_link_counts",
                "timespans_id",
                // MAX(timespans_id) = 1_362_209 in source table; 10 chunks
                150_000,
                // Citus doesn't like it when we join local (unsharded) and distributed tables in this case therefore we
                // create a temporary table first
                Arrays.asList(
                        String.format("""
                                CREATE TEMPORARY TABLE temp_chunk_timespans AS
                                    SELECT
                                        timespans_id::INT,
                                        topics_id::INT
                                    FROM public.timespans
                                    WHERE timespans_id BETWEEN %s AND %s
                                    """, START_ID_MARKER, END_ID_MARKER),
                        String.format("""
                                WITH deleted_rows AS (
                                    DELETE FROM unsharded_snap.medium_link_counts
                                    USING temp_chunk_timespans
                                    WHERE
                                        unsharded_snap.medium_link_counts.timespans_id = temp_chunk_timespans.timespans_id AND
                                        unsharded_snap.medium_link_counts.timespans_id BETWEEN %s AND %s
                                    RETURNING
                                        temp_chunk_timespans.topics_id,
                                        unsharded_snap.medium_link_counts.timespans_id,
                                        unsharded_snap.medium_link_counts.media_id,
                                        unsharded_snap.medium_link_counts.sum_media_inlink_count,
                                        unsharded_snap.medium_link_counts.media_inlink_count,
                                        unsharded_snap.medium_link_counts.inlink_count,
                                        unsharded_snap.medium_link_counts.outlink_count,
                                        unsharded_snap.medium_link_counts.story_count,
                                        unsharded_snap.medium_link_counts.facebook_share_count,
                                        unsharded_snap.medium_link_counts.sum_post_count,
                                        unsharded_snap.medium_link_counts.sum_author_count,
                                        unsharded_snap.medium_link_counts.sum_channel_count
                                )
                                INSERT INTO sharded_snap.medium_link_counts (
                                    topics_id,
                                    timespans_id,
                                    media_id,
                                    sum_media_inlink_count,
                                    media_inlink_count,
                                    inlink_count,
                                    outlink_count,
                                    story_count,
                                    facebook_share_count,
                                    sum_post_count,
                                    sum_author_count,
                                    sum_channel_count
                                )
                                    SELECT
                                        topics_id::BIGINT,
                                        timespans_id::BIGINT,
                                        media_id::BIGINT,
                                        sum_media_inlink_count::BIGINT,
                                        media_inlink_count::BIGINT,
                                        inlink_count::BIGINT,
                                        outlink_count::BIGINT,
                                        story_count::BIGINT,
                                        facebook_share_count::BIGINT,
                                        sum_post_count::BIGINT,
                                        sum_author_count::BIGINT,
                                        sum_channel_count::BIGINT
                                    FROM deleted_rows
                                ON CONFLICT (topics_id, timespans_id, media_id) DO NOTHING
                                    """, START_ID_MARKER, END_ID_MARKER),
                        "TRUNCATE temp_chunk_timespans",
                        "DROP TABLE temp_chunk_timespans"
                )
        );

        this.moveTable(
                "unsharded_snap.medium_links",
                "timespans_id",
                // MAX(timespans_id) = 1_362_209 in source table; 10 chunks
                150_000,
                Arrays.asList(
                        // Citus doesn't like it when we join local (unsharded) and distributed tables in this case
                        // therefore we create a temporary table first
                        String.format("""
                                CREATE TEMPORARY TABLE temp_chunk_timespans AS
                                    SELECT
                                        timespans_id::INT,
                                        topics_id::INT
                                    FROM public.timespans
                                    WHERE timespans_id BETWEEN %s AND %s
                                    """, START_ID_MARKER, END_ID_MARKER),
                        String.format("""
                                WITH deleted_rows AS (
                                    DELETE FROM unsharded_snap.medium_links
                                    USING temp_chunk_timespans
                                    WHERE
                                        unsharded_snap.medium_links.timespans_id
                                            = temp_chunk_timespans.timespans_id AND
                                        unsharded_snap.medium_links.timespans_id BETWEEN %s AND %s
                                    RETURNING
                                        temp_chunk_timespans.topics_id,
                                        unsharded_snap.medium_links.timespans_id,
                                        unsharded_snap.medium_links.source_media_id,
                                        unsharded_snap.medium_links.ref_media_id,
                                        unsharded_snap.medium_links.link_count
                                )
                                INSERT INTO sharded_snap.medium_links (
                                    topics_id,
                                    timespans_id,
                                    source_media_id,
                                    ref_media_id,
                                    link_count
                                )
                                    SELECT
                                        topics_id::BIGINT,
                                        timespans_id::BIGINT,
                                        source_media_id::BIGINT,
                                        ref_media_id::BIGINT,
                                        link_count::BIGINT
                                    FROM deleted_rows
                                ON CONFLICT (topics_id, timespans_id, source_media_id, ref_media_id) DO NOTHING
                                    """, START_ID_MARKER, END_ID_MARKER),
                        "TRUNCATE temp_chunk_timespans",
                        "DROP TABLE temp_chunk_timespans"
                )
        );

        this.moveTable(
                "unsharded_snap.timespan_posts",
                "timespans_id",
                // MAX(timespans_id) = 1_362_209 in source table; 10 chunks
                150_000,
                Arrays.asList(
                        // Citus doesn't like it when we join local (unsharded) and distributed tables in this case
                        // therefore we create a temporary table first
                        String.format("""
                                CREATE TEMPORARY TABLE temp_chunk_timespans AS
                                    SELECT
                                        timespans_id::INT,
                                        topics_id::INT
                                    FROM public.timespans
                                    WHERE timespans_id BETWEEN %s AND %s
                                    """, START_ID_MARKER, END_ID_MARKER),
                        String.format("""
                                WITH deleted_rows AS (
                                    DELETE FROM unsharded_snap.timespan_posts
                                    USING temp_chunk_timespans
                                    WHERE
                                        unsharded_snap.timespan_posts.timespans_id
                                            = temp_chunk_timespans.timespans_id AND
                                        unsharded_snap.timespan_posts.timespans_id BETWEEN %s AND %s
                                    RETURNING
                                        temp_chunk_timespans.topics_id,
                                        unsharded_snap.timespan_posts.timespans_id,
                                        unsharded_snap.timespan_posts.topic_posts_id
                                )
                                INSERT INTO sharded_snap.timespan_posts (
                                    topics_id,
                                    timespans_id,
                                    topic_posts_id
                                )
                                    SELECT
                                        topics_id::BIGINT,
                                        timespans_id::BIGINT,
                                        topic_posts_id::BIGINT
                                    FROM deleted_rows
                                    """, START_ID_MARKER, END_ID_MARKER),
                        "TRUNCATE temp_chunk_timespans",
                        "DROP TABLE temp_chunk_timespans"
                )
        );

        this.moveTable(
                "unsharded_snap.live_stories",
                "topic_stories_id",
                // MAX(topic_stories_id) = 165_082_931 in source table; 17 chunks
                10_000_000,
                List.of(String.format("""
                        WITH deleted_rows AS (
                            DELETE FROM unsharded_snap.live_stories
                            WHERE topic_stories_id BETWEEN %s AND %s
                            RETURNING
                                topics_id,
                                topic_stories_id,
                                stories_id,
                                media_id,
                                url,
                                guid,
                                title,
                                normalized_title_hash,
                                description,
                                publish_date,
                                collect_date,
                                full_text_rss,
                                language
                        )
                        INSERT INTO sharded_snap.live_stories (
                            topics_id,
                            topic_stories_id,
                            stories_id,
                            media_id,
                            url,
                            guid,
                            title,
                            normalized_title_hash,
                            description,
                            publish_date,
                            collect_date,
                            full_text_rss,
                            language
                        )
                            SELECT
                                topics_id::BIGINT,
                                topic_stories_id::BIGINT,
                                stories_id::BIGINT,
                                media_id::BIGINT,
                                url::TEXT,
                                guid::TEXT,
                                title,
                                normalized_title_hash,
                                description,
                                publish_date,
                                collect_date,
                                full_text_rss,
                                language
                            FROM deleted_rows
                            """, START_ID_MARKER, END_ID_MARKER))
        );
    }
}
