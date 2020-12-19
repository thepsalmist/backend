import mediawords.db
from mediawords.test.db.create import create_test_topic
from mediawords.test.solr import create_test_story_stack_for_indexing, setup_test_index
import topics_mine.mine

from mediawords.util.log import create_logger
log = create_logger(__name__)

def test_import_solr_seed_query_month():
    db = mediawords.db.connect_to_db()
    
    num_stories = 200

    topic = create_test_topic(db, 'foo')

    stack = {'medium_1': {'feed_1': [f"story_{_}" for _ in range(num_stories)]}}
    create_test_story_stack_for_indexing(db, stack)

    all_media = db.query("select * from media").hashes()
    all_stories = db.query("select * from stories").hashes()

    topic['start_date'] = '2020-01-01'
    topic['end_date'] = '2020-06-01'
    topic['solr_seed_query'] = '*:*'
    topic['solr_seed_query_run'] = False

    db.update_by_id('topics', topic['topics_id'], topic)

    for m in all_media:
        db.query(
            "insert into topics_media_map (topics_id, media_id) values (%(a)s, %(b)s)",
            {'a': topic['topics_id'], 'b': m['media_id']})

    # distribute one story each day.  this is kludgy but should work from a fresh databse with
    # sequential stories_ids.  assumes that there are more stories than days in the date range above
    stories = db.query("select * from stories").hashes()
    for (i, story) in enumerate(stories):
        db.query(
                """
                update stories set publish_date = %(a)s::timestamp + ((%(b)s || ' days')::interval)
                    where stories_id = %(c)s
                """,
                {'a': topic['start_date'], 'b': i, 'c': story['stories_id']})

    setup_test_index(db)

    i = 0
    while topics_mine.mine.import_solr_seed_query_month(db, topic, i):
        date_stories = db.query(
            """
            select * from stories
                where
                    publish_date >= %(a)s::timestamp + ((%(b)s || ' months')::interval) and
                    publish_date <= %(a)s::timestamp + ((%(c)s || ' months')::interval) and
                    publish_date <= %(d)s
            """,
            {'a': topic['start_date'], 'b': i, 'c': i + 1, 'd': topic['end_date']}).hashes()

        date_stories_urls = [s['url'] for s in date_stories]

        count_topic_seed_urls = db.query(
            "select count(distinct url) from topic_seed_urls where url = any(%(a)s)",
            {'a': date_stories_urls}).flat()[0]

        assert len(date_stories) > 0, f"offset {i}"
        assert len(date_stories) == count_topic_seed_urls, f"topic seed urls for month offset {i}"

        i += 1

        

