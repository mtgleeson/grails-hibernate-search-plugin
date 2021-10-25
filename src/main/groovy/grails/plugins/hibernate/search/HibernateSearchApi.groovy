/* Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package grails.plugins.hibernate.search

import grails.core.GrailsClass
import grails.plugins.hibernate.search.component.AboveComponent
import grails.plugins.hibernate.search.component.BelowComponent
import grails.plugins.hibernate.search.component.BetweenComponent
import grails.plugins.hibernate.search.component.Component
import grails.plugins.hibernate.search.component.FuzzyComponent
import grails.plugins.hibernate.search.component.KeywordComponent
import grails.plugins.hibernate.search.component.Leaf
import grails.plugins.hibernate.search.component.MustComponent
import grails.plugins.hibernate.search.component.MustNotComponent
import grails.plugins.hibernate.search.component.PhraseComponent
import grails.plugins.hibernate.search.component.ShouldComponent
import grails.plugins.hibernate.search.component.SimpleQueryStringComponent
import grails.plugins.hibernate.search.component.WildcardComponent
import grails.plugins.hibernate.search.filter.FilterFactory
import groovy.util.logging.Slf4j
import org.hibernate.SessionFactory
import org.hibernate.Transaction
import org.hibernate.search.engine.search.predicate.SearchPredicate
import org.hibernate.search.engine.search.predicate.dsl.BooleanPredicateClausesStep
import org.hibernate.search.engine.search.predicate.dsl.SearchPredicateFactory
import org.hibernate.search.engine.search.projection.dsl.FieldProjectionValueStep
import org.hibernate.search.engine.search.projection.dsl.ProjectionFinalStep
import org.hibernate.search.engine.search.projection.dsl.SearchProjectionFactory
import org.hibernate.search.engine.search.query.SearchResult
import org.hibernate.search.engine.search.query.dsl.SearchQueryOptionsStep
import org.hibernate.search.engine.search.query.dsl.SearchQueryWhereStep
import org.hibernate.search.engine.search.sort.dsl.FieldSortOptionsStep
import org.hibernate.search.engine.search.sort.dsl.SearchSortFactory
import org.hibernate.search.engine.search.sort.dsl.SortOrder
import org.hibernate.search.mapper.orm.Search
import org.hibernate.search.mapper.orm.entity.SearchIndexedEntity
import org.hibernate.search.mapper.orm.mapping.SearchMapping
import org.hibernate.search.mapper.orm.massindexing.MassIndexer
import org.hibernate.search.mapper.orm.scope.SearchScope
import org.hibernate.search.mapper.orm.session.SearchSession

@Slf4j
@SuppressWarnings('GroovyUnusedDeclaration')
class HibernateSearchApi {

    private static final List MASS_INDEXER_METHODS = MassIndexer.methods.findAll {it.returnType == MassIndexer}*.name

    private final GrailsClass grailsDomainClass
    private final Class clazz
    private final instance
    private final staticContext
    private final SessionFactory sessionFactory

    private FieldSortOptionsStep fieldSortOptionsStep
    private SearchScope searchScope
    private MassIndexer massIndexer

    private Integer maxResults = 0
    private Integer offset = 0
    private final List<SearchPredicate> filterPredicates = []
    private final List<String> projections = []

    private Component root
    private Component currentNode

    static Closure defineSearchQuery(@DelegatesTo(HibernateSearchApi) closure) {
        closure
    }

    HibernateSearchApi(GrailsClass domainClass, instance, SessionFactory sessionFactory) {
        this.grailsDomainClass = domainClass
        this.clazz = domainClass.clazz
        this.instance = instance
        this.staticContext = instance == null
        this.sessionFactory = sessionFactory
    }

    HibernateSearchApi(GrailsClass domainClass, SessionFactory sessionFactory) {
        this(domainClass, null, sessionFactory)
    }

    SearchSession getSearchSession() {
        Search.session(sessionFactory.currentSession)
    }

    SearchPredicateFactory getSearchPredicateFactory() {
        searchScope.predicate()
    }

    /**
     *
     * @param searchDsl
     * @return the results for this search
     */
    List list(@DelegatesTo(HibernateSearchApi) Closure searchDsl = null) {

        initSearch()

        invokeClosureNode searchDsl

        SearchQueryOptionsStep searchQuery = createFullTextQuery()

        if (fieldSortOptionsStep) {
            searchQuery.sort(fieldSortOptionsStep.toSort())
        }

        SearchResult searchResult
        if (maxResults > 0) {
            searchResult = offset ? searchQuery.fetch(offset, maxResults) : searchQuery.fetch(maxResults)
        } else {
            searchResult = searchQuery.fetchAll()
        }
        searchResult.hits()
    }

    /**
     *
     * @param searchDsl
     * @return the number of hits for this search.
     */
    int count(@DelegatesTo(HibernateSearchApi) Closure searchDsl = null) {

        initSearch()

        invokeClosureNode searchDsl

        SearchQueryOptionsStep searchQuery = createFullTextQuery()
        searchQuery.fetchTotalHitCount()
    }

    /**
     * create an initial Lucene index for the data already present in your database
     *
     * @param massIndexerDsl
     */
    void createIndexAndWait(@DelegatesTo(HibernateSearchApi) Closure massIndexerDsl = null) {

        massIndexer = searchSession.massIndexer(clazz)

        invokeClosureNode massIndexerDsl

        massIndexer.startAndWait()
    }

    void maxResults(int maxResults) {
        this.maxResults = maxResults
    }

    void offset(int offset) {
        this.offset = offset
    }

    void projection(String... projection) {
        this.projections.addAll(projection)
    }

    @Deprecated
    void criteria(Closure criteria) {
        log.warn(
            'DEPRECATED: Hibernate Search 6 does not allow criteria to be added to a search (https://docs.jboss.org/hibernate/search/6' +
            '.0/migration/html_single/#searching-fulltextquery-setCriteriaQuery)')
    }

    void sort(String field, String order = 'ASC') {
        SearchSortFactory searchSortFactory = fieldSortOptionsStep ? fieldSortOptionsStep.then() : searchScope.sort()
        fieldSortOptionsStep = searchSortFactory.field(field).order(SortOrder.valueOf(order.toUpperCase()))
    }

    /**
     * Execute code within programmatic hibernate transaction
     *
     * @param callable a closure which takes an Hibernate Transaction as single argument.
     * @return the result of callable
     */
    void withTransaction(Closure callable) {

        Transaction transaction = searchSession.toOrmSession().beginTransaction()

        try {

            def result = callable.call(transaction)

            if (transaction.isActive()) {
                transaction.commit()
            }

            result

        } catch (ex) {
            transaction.rollback()
            throw ex
        }
    }

    /**
     *
     * @return the scoped analyzer for this entity
     */
    @Deprecated
    def getAnalyzer() {
        log.warn(
            'DEPRECATED: Hibernate Search 6 does not allow a class level analyzer to be defined.' +
            'All fields must specify their analyzer explicitly or rely on the global (default) analyzer.' +
            'https://docs.jboss.org/hibernate/search/6.0/migration/html_single/#analyzer')
    }

    /**
     * Force the (re)indexing of a given <b>managed</b> object.
     * Indexation is batched per transaction: if a transaction is active, the operation
     * will not affect the index at least until commit.
     */
    void index() {
        if (!staticContext) {
            searchSession.indexingPlan().addOrUpdate(instance)
        } else {
            throw new MissingMethodException('index', getClass(), [] as Object[])
        }
    }

    /**
     * Remove the entity from the index.
     */
    void purge() {
        if (!staticContext) {
            searchSession.indexingPlan().purge(clazz, instance.id, null)
        } else {
            throw new MissingMethodException('purge', getClass(), [] as Object[])
        }
    }

    void purgeAll() {
        searchSession.workspace(clazz).purge()
    }

    void filter(SearchPredicate filterPredicate) {
        filterPredicates << filterPredicate
    }

    void filter(FilterFactory filterFactory, Map params) {
        filterPredicates << filterFactory.create(searchPredicateFactory, params)
    }

    @Deprecated
    void filter(String filterName) {
        log.warn(
            'DEPRECATED: Filters must now be defined using factories which return a SearchPredicate. https://docs.jboss.org/hibernate/search/6' +
            '.0/migration/html_single/#full-text-filter')
    }

    @Deprecated
    void filter(Map<String, Object> filterParams) {
        log.warn(
            'DEPRECATED: Filters must now be defined using factories which return a SearchPredicate. https://docs.jboss.org/hibernate/search/6' +
            '.0/migration/html_single/#full-text-filter')
    }

    @Deprecated
    void filter(String filterName, Map<String, Object> filterParams) {
        log.warn(
            'DEPRECATED: Filters must now be defined using factories which return a SearchPredicate. https://docs.jboss.org/hibernate/search/6' +
            '.0/migration/html_single/#full-text-filter')
    }

    void below(String field, below, Map optionalParams = [:]) {
        addLeaf new BelowComponent([searchScope: searchScope, field: field, below: below] + optionalParams)
    }

    void above(String field, above, Map optionalParams = [:]) {
        addLeaf new AboveComponent([searchScope: searchScope, field: field, above: above] + optionalParams)
    }

    void between(String field, from, to, Map optionalParams = [:]) {
        addLeaf new BetweenComponent([searchScope: searchScope, field: field, from: from, to: to] + optionalParams)
    }

    void keyword(String field, matching, Map optionalParams = [:]) {
        addLeaf new KeywordComponent([searchScope: searchScope, field: field, matching: matching] + optionalParams)
    }

    void fuzzy(String field, matching, Map optionalParams = [:]) {
        addLeaf new FuzzyComponent([searchScope: searchScope, field: field, matching: matching] + optionalParams)
    }

    void wildcard(String field, matching, Map optionalParams = [:]) {
        addLeaf new WildcardComponent([searchScope: searchScope, field: field, matching: matching] + optionalParams)
    }

    void phrase(String field, sentence, Map optionalParams = [:]) {
        addLeaf new PhraseComponent([searchScope: searchScope, field: field, sentence: sentence] + optionalParams)
    }

    void simpleQueryString(String queryString, Map optionalParams = [:], String field, String... fields) {
        addComponent new SimpleQueryStringComponent([searchScope: searchScope,
                                                     field      : field, fields: fields,
                                                     queryString: queryString] + optionalParams)
    }

    void simpleQueryString(String queryString, String field, float fieldBoost, List<String> fields, float fieldsBoost, Map optionalParams = [:]) {
        addComponent new SimpleQueryStringComponent([searchScope: searchScope,
                                                     field      : field, fieldBoost: fieldBoost,
                                                     fields     : fields, fieldsBoost: fieldsBoost,
                                                     queryString: queryString] + optionalParams)
    }

    void simpleQueryString(String queryString, Map<String, Float> fieldsAndBoost, Map optionalParams = [:]) {
        addComponent new SimpleQueryStringComponent([searchScope   : searchScope,
                                                     fieldsAndBoost: fieldsAndBoost,
                                                     queryString   : queryString] + optionalParams)
    }

    void must(Closure arg) {
        addComposite new MustComponent(searchScope: searchScope), arg
    }

    void should(Closure arg) {
        addComposite new ShouldComponent(searchScope: searchScope), arg
    }

    void mustNot(Closure arg) {
        addComposite new MustNotComponent(searchScope: searchScope), arg
    }

    Object invokeMethod(String name, Object args) {
        if (name in MASS_INDEXER_METHODS) {
            massIndexer = massIndexer.invokeMethod(name, args) as MassIndexer
        } else {
            throw new MissingMethodException(name, getClass(), args)
        }
    }

    void invokeClosureNode(@DelegatesTo(HibernateSearchApi) Closure callable) {
        if (!callable) return

        callable.delegate = this
        callable.resolveStrategy = Closure.DELEGATE_FIRST
        callable.call()
    }

    Map<String, Object> getIndexedProperties() {
        [:]
    }

    Collection<SearchIndexedEntity> getIndexedEntities() {
        SearchMapping searchMapping = Search.mapping(searchSession.toOrmSession().sessionFactory)
        searchMapping.allIndexedEntities()
    }

    private SearchQueryOptionsStep createFullTextQuery() {

        SearchPredicate primarySearchPredicate = root.createSearchPredicate()
        SearchPredicateFactory predicateFactory = searchScope.predicate()
        SearchPredicate searchPredicate = primarySearchPredicate

        if (filterPredicates) {
            BooleanPredicateClausesStep step = predicateFactory.bool().must(primarySearchPredicate)
            filterPredicates.each {filterPredicate ->
                step.filter(filterPredicate)
            }
            searchPredicate = step.toPredicate()
        }

        SearchQueryWhereStep whereStep = searchSession.search(clazz)
        if (projections) {
            SearchProjectionFactory projectionFactory = searchScope.projection()
            ProjectionFinalStep projectionFinalStep

            if (projections.size() == 1) {
                projectionFinalStep = projectionFactory.field(projections.first())
            } else {
                FieldProjectionValueStep[] fields = projections.collect {projectionFactory.field(it)}.toArray() as FieldProjectionValueStep[]
                projectionFinalStep = projectionFactory.composite(fields)
            }
            whereStep = whereStep.select(projectionFinalStep.toProjection())
        }

        whereStep.where(searchPredicate)
    }

    private initSearch() {
        searchScope = searchSession.scope(clazz)
        root = new MustComponent(searchScope: searchScope)
        currentNode = root
    }

    private void addComponent(Component component) {
        currentNode << component
    }

    private void addComposite(Component component, Closure arg) {

        currentNode << component
        currentNode = component

        invokeClosureNode arg

        currentNode = currentNode?.parent ?: root
    }

    private void addLeaf(Leaf leaf) {
        currentNode << leaf
    }
}