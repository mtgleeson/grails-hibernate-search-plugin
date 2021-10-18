package grails.plugins.hibernate.search.filter

import org.hibernate.search.engine.search.predicate.SearchPredicate
import org.hibernate.search.engine.search.predicate.dsl.SearchPredicateFactory

/**
 * @since 13/10/2021
 */
interface FilterFactory {
    SearchPredicate create(SearchPredicateFactory factory, Map params)
}