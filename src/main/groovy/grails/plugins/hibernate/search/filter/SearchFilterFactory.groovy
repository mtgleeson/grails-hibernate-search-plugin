package grails.plugins.hibernate.search.filter

import org.hibernate.search.engine.search.predicate.SearchPredicate
import org.hibernate.search.engine.search.predicate.dsl.BooleanPredicateClausesStep
import org.hibernate.search.engine.search.predicate.dsl.PredicateFinalStep
import org.hibernate.search.engine.search.predicate.dsl.SearchPredicateFactory

/**
 * @since 12/10/2021
 */
class SearchFilterFactory {

    private SearchFilterFactory() {

    }

    static SearchPredicate create(SearchPredicateFactory factory, Map filterParams) {

        if (filterParams.size() == 1) {
            Map.Entry entry = filterParams.entrySet().toList().first()
            return buildFilterStep(factory, entry).toPredicate()
        }

        BooleanPredicateClausesStep step = factory.bool()

        filterParams.each {entry ->
            step = step.must(buildFilterStep(factory, entry))
        }

        step.toPredicate()
    }

    static PredicateFinalStep buildFilterStep(SearchPredicateFactory factory, Map.Entry entry) {
        factory.match().field(entry.key.toString()).matching(entry.value.toString())
    }
}
