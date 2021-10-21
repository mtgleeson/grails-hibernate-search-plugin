package grails.plugins.hibernate.search.mapper.orm.massindexing

import groovy.util.logging.Slf4j
import org.hibernate.Session
import org.hibernate.SessionFactory
import org.hibernate.search.mapper.orm.Search
import org.hibernate.search.mapper.orm.mapping.SearchMapping
import org.hibernate.search.mapper.orm.massindexing.MassIndexer
import org.hibernate.search.mapper.orm.session.SearchSession
import org.springframework.context.ApplicationContext

/**
 * @since 18/10/2021
 */
@Slf4j
class RebuildOnStartMassIndexer {

    private final SearchSession searchSession
    private final MassIndexer massIndexer
    private final SearchMapping searchMapping
    boolean rebuild = false
    boolean block = true

    RebuildOnStartMassIndexer(Session session, def arguments) {
        searchMapping = Search.mapping(session.sessionFactory)
        searchSession = Search.session(session)

        massIndexer = searchSession.massIndexer()
        if (arguments instanceof Boolean) {
            rebuild = arguments
        } else if (arguments instanceof String) {
            rebuild = arguments.toBoolean()
        } else {
            Map<String, Object> argMap = arguments as Map<String, Object>
            rebuild = true
            Boolean blocking = argMap.remove('block')
            if (blocking == null) blocking = true
            block = blocking

            argMap.each {k, v ->
                massIndexer.invokeMethod(k, v)
            }
        }

    }

    void doRebuild() {
        if (rebuild) {
            log.debug('Reindexing entities {}', searchMapping.allIndexedEntities().collect {it.jpaName()}.sort())
            if (block) {
                log.warn 'Rebuilding indexes of all indexed entity types'
                massIndexer.startAndWait()
            } else {
                log.warn 'Rebuilding indexes of all indexed entity types asynchronously'
                massIndexer.start()
            }
        }
    }

    static rebuild(ApplicationContext applicationContext, def arguments) {
        SessionFactory sessionFactory = applicationContext.getBean(SessionFactory)
        Session session = sessionFactory.openSession()

        RebuildOnStartMassIndexer massIndexer = new RebuildOnStartMassIndexer(session, arguments)
        massIndexer.doRebuild()

        session.close()
    }


}
