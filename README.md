# Grails Hibernate Search Plugin

This plugin aims to integrate Hibernate Search 6+ features to Grails in very few steps.

It is worth noting that Hibernate Search 6 is a vast improvement over previous versions as they have added and improved the programmatic access in all areas. This plugin is
aimed to make life easier in linking the Grails domains into the Hibernate Search indexing system, as annotations cannot be applied to the static definitions used in Grails
domain classes. It also supplies a convenient API class to build HS queries quickly, however once you have indexed your domains using this plugin it is much easier in HS6 to
open a new SearchSession and compose your own queries thanks to their new DSL.

- [Grails Hibernate Search Plugin](#grails-hibernate-search-plugin)
    * [Getting started](#getting-started)
    * [Configuration](#configuration)
    * [Indexing](#indexing)
        + [Indexing Domain Class](#mark-your-domain-classes-as-indexable)
        + [Available Mappings](#available-mappings)
            + [Property Mappings](#property-mappings)
            + [Field Mappings](#field-mappings)
        + [Indexing the Data](#indexing-the-data)
  * [Search](#search)
    + [Retrieving search results](#retrieving-the-results)
    + [Mixing with criteria query](#mixing-with-criteria-query)
    + [Performing SimpleQueryString Searches](#performing-simplequerystring-searches)
    + [Sorting results](#sorting-the-results)
    + [Counting results](#counting-the-results)
    + [Additional features](#additional-features)
  * [Analysis](#analysis)
    + [Defining Analyzers](#define-named-analyzers)
    + [Using Defined Analyzers](#use-named-analyzers)
  * [Normalizer](#normalizer)
    + [Defining Normalizers](#define-named-normalizers)
    + [Using Defined Normalizers](#use-named-normalizer)
  * [Filters](#filters)
    + [Defining Filters](#define-named-filters)
    + [Using Defined Filters](#filter-query-results)
    * [Options](#options)
    * [Migrating from 2.x to 3.x](#migrating-from-v2.x-to-v3.x)
    * [Notes](#notes)
        + [Updating from 2.2 to 2.3](#updating-from-2.2-to-2.3)
        + [runtime.groovy vs application.groovy](#runtime.groovy-vs-application.groovy)
        + [IDE Integration](#ide-integration)
        + [SessionFactory failures during startup](#sessionfactory-failures-during-startup)
    * [Examples](#examples)
    * [Change log](#change-log)
    * [Authors](#authors)
    * [Development / Contribution](#development-contribution)
    * [License](#license)

## Getting started

If you don't want to start from the [template project](#examples), you could start a fresh project:

**Note** HS6 has provided a much cleaner disconnect between Hibernate Search and the actual "backend" which does the indexing. This plugin only requires the core components,
your application will have to add the dependency for the backend type which you want to use Currently the options are:

* [Lucene](https://lucene.apache.org/) : `org.hibernate.search:hibernate-search-backend-lucene:6.1.0.Beta1`
* [Elasticsearch](https://www.elastic.co/guide/en/elasticsearch/reference/7.10) : `org.hibernate.search:hibernate-search-backend-elasticsearch:6.1.0.Beta1`

And add the following to your dependencies

```
  compile("org.hibernate.search:hibernate-search-mapper-orm:6.1.0.Beta1")
  compile("org.grails.plugins:hibernate5:7.2.0")
  compile("org.grails.plugins:cache")
  compile("org.hibernate:hibernate-core:5.6.3.Final")
  compile("org.hibernate:hibernate-ehcache:5.6.3.Final")
  // dont forget your choice of backend
```

Please note the following

* Version 3.x of this plugin requires Hibernate Search 6.0.8+ as there is a bug inside previous versions which will not let Hibernate Search work with groovy. Currently this
  version is built and supplied externally as 6.1.0.Beta1.
* Hibernate Search 6+ requires Hibernate 5.4.4+ if you are using an older version of Hibernate and cannot upgrade then please use the v2.x version of this plugin

## Configuration

By default, the plugin stores your indexes in this directory:

```
 ~/.grails/${grailsVersion}/projects/${yourProjectName}/lucene-index/development/
```

You can override this configuration in your application.yml

```yml
hibernate:
    cache:
        use_second_level_cache: true
        use_query_cache: true
        provider_class: net.sf.ehcache.hibernate.EhCacheProvider
        region:
            factory_class: org.hibernate.cache.ehcache.SingletonEhCacheRegionFactory
    search:
        backend:
        	directory: 
        		root: '/path/to/your/indexes'
```

See [Hibernate Search Configuration](https://docs.jboss.org/hibernate/search/6.0/reference/en-US/html_single/#search-configuration) documentation for all available
configuration.

## Indexing

See [Hibernate Search Search Mapping](https://docs.jboss.org/hibernate/search/6.0/reference/en-US/html_single/#hsearch-mapping-programmaticapi) for full mapping information.
This plugin defines a closure which performs some of the basic mapping features via simple map calls, however there is the ability to access the full programmatic api for
index mapping through this closure.

### Mark your domain classes as indexable

* Indexing in HS6 is inherited so any indexing performed on one domain will be inherited into its sub classes.
* You can use properties from super class and traits with no additional configuration
* You can either define the indexing using the plugin's traditional map interface or by defining a closure which will create a PropertyMappingStep and then expect you to code
  all the required indexing using Hibernate Search's programmatic API. We have supplied a static method for convenience which you can use when using the closure
  method, `SearchMappingEntityConfig#propertyMapping`, this returns the closure you supply but allows the IDE code completion.

Add a static `search` closure as following:

```groovy
class MyDomainClass {

    String author
    String body
    Date publishedDate
    String summary
    String title
    Status status
    Double price
    BigDecimal tax
    Integer someInteger
    String description
    MyOtherDomainClass myOtherDomainClass

    enum Status {
        DISABLED, PENDING, ENABLED
    }

  static hasMany = [categories: Category, items: Item]

  static search = {
    // fields
    	id searchable: 'yes', projectable: 'yes'
        author searchable: 'yes'
        body termVector: 'with_positions'
        title searchable: 'yes', sortable: [name: title_sort, normalizer: 'lowerCaseFilter']
        status searchable: 'yes', sortable: true
        categories indexEmbedded: true
        items indexEmbedded: [depth: 2] // configure the depth indexing
        price analyze: false
	tax searchable: 'yes', decimalScale: 2
        someInteger searchable: 'yes', bridge: ['class': PaddedIntegerBridge]
        description searchable: 'yes', additionalFieldOptionsMapping: {
         // Act on the PropertyMappingStandardFieldOptionsStep directly using the HS programmatic API
         // This closure allows you to add additional steps which this plugin has not coded
        }
        myOtherDomainClass {
         // Act on the PropertyMappingStep directly using the HS programmatic API
         // Using this closure will create a new PropertyMappingStep and expect you to code the full indexing setup for this property
        }
    }

}
```

This static property indicates which fields should be indexed and describes how the field has to be indexed, this is done using the
[SearchMappingEntityConfig](src/main/groovy/grails/plugins/hibernate/search/config/SearchMappingEntityConfig.groovy)

### Available Mappings

Hibernate Search has
[clearly defined types](https://docs.jboss.org/hibernate/search/6.0/reference/en-US/html_single/#mapper-orm-directfieldmapping-annotations)
of field mapping depending on the property class. This plugin will create a Field mapping based off the definitions, you can control how the GenericField acts by defining your
own
[Bridge](https://docs.jboss.org/hibernate/search/6.0/reference/en-US/html_single/#search-mapping-bridge)

* FullTextField: Applied to any String, Character or Enum
* KeywordField: Applied to any String, Character or Enum if `analyze` is `false`
* ScaledNumberField: Applied to BigDecimal and BigInteger when declaring a decimalScale
* GenericField: Applied to any other type of class

#### Property Mappings

`binder`
: Adds a [Property Binder](https://docs.jboss.org/hibernate/search/6.0/reference/en-US/html_single/#section-field-bridge). This is required where you have defined a
PropertyBridge and need to bind that PropertyBridge to this property. The value is one of

* A PropertyBinder instance
* A PropertyBinder class which can be instantiated with no args
* A Map with the key:value pairs
    * class: PropertyBinder class
    * args: The args required to instantiate the class

`indexEmbedded`
: Indexes the property as [index embedded](https://docs.jboss.org/hibernate/search/6.0/reference/en-US/html_single/#search-mapping-associated)
where the value is one of:

* boolean true to mark as a plain embedded index
* Map with any of the following key:value pairs
    * name: String index name for the embedded index
    * depth: integer depth to embed to
    * includeEmbeddedObjectId: boolean to include the object id in the index
    * associationInverseSide: the inverse side of an association which will be needed by HS to map the indexing dependencies. The indexing requirement can be turned off using
      the indexingDependency mapping. One of the following which defines the property this property associated with, the supplied value/s will be converted to a
      PojoModelPathValueNode object.
        * String path to the property
        * String Iterable of paths to the property
        * PojoModelPathValueNode
    * includePaths: String Collection of paths to limit the embedded index to. Without this the index will embed everything from the associated class which has been marked as
      searchable

`indexingDependency`
: Aids Hibernate Search in mapping domain associations. HS usually uses the Hibernate annotations to do this, however these don't exist inside Grails, therefore expect to have
to use this mapping entry for any properties you want to index which are mapped to another Grails domain. This is also used to define derived properties and when they should
be reindexed. This expects a map with the following key:value pairs

* [derivedFrom](https://docs.jboss.org/hibernate/search/6.0/reference/en-US/html_single/#mapper-orm-reindexing-derivedfrom): One of the following which defines the property
  this property is derived from. The supplied value/s will be converted to a PojoModelPathValueNode object.
    * String path to the property
    * String Iterable of paths to the property
    * PojoModelPathValueNode
* [reindexOnUpdate](https://docs.jboss.org/hibernate/search/6.0/reference/en-US/html_single/#mapper-orm-reindexing-reindexonupdate): String defining when the index should be
  refreshed. To disable index updating on embedded or derived properties you should set this to `shallow` or `no`.

#### Field Mappings

The plugin provides direct mapped access to most of the HS
[field attributes](https://docs.jboss.org/hibernate/search/6.0/reference/en-US/html_single/#mapper-orm-directfieldmapping-annotation-attributes). If you require any attributes
which have not been mapped then you should make use of the field closure mapping argument `additionalFieldOptionsMapping`. As the Hibernate Search documentation details what
all these attributes are, we will just list the expected values.

`searchable`
: String of `default`, `yes` or `no`
Note this is NOT added by default however if it is not added then HS will fall back on the chosen backend default.

`projectable`
: String of `default`, `yes` or `no`

`norms`
: String of `default`, `yes` or `no`

`bridge`
:This is used to apply a ValueBridge ONLY to a property, it should be one of the following

* Class which extends ValueBridge
* Instance of a class which extends ValueBridge

`termVector`
: Can only be applied to FullTextFields. String of `default`, `yes`, `no`, `with_positions`, `with_offsets`, `with_positions_offsets`, `with_positions_payloads`
, `with_positions_offsets_payloads`

`analyzer`
: Can only be applied to FullTextFields. String name of an analyser to be applied. Analyzers are defined inside a bean configurer, see [Analyzers](#analysers).

`normalizer`
: Can only be applied to KeywordFields String name of a normalizer to be applied. Normalizers are defined inside a bean configurer, see [Normalizers](#normalizers).

`additionalFieldOptionsMapping`
: Closure which delegates to the PropertyMappingKeywordFieldOptionsStep built by the mapping so far, ideally the closure should be applied last. For convenience inside an IDE
you can call the static method `SearchMappingEntityConfig#additionalFieldOptionsMapping` which will return the closure you pass to the method, but provides the necessary
delegation annotations to help an IDE in code completion.

`sortable`
: Can only be to KeywordFields or GenericFields. Either a boolean `true` or a Map with the following key:value pairs.

* name: Name for the sortable field, if not supplied then the property index name will be used with a suffix `_sort`
* normalizer: Can only be applied to KeywordFields. String name of a normalizer to be applied. Normalizers are defined inside a bean configurer,
  see [Normalizers](#normalizers).

Also, the plugin lets you to mark your domain classes as indexable with the Hibernate Search annotations,
see [Hibernate Search Search Mapping](https://docs.jboss.org/hibernate/search/6.0/reference/en-US/html_single/#search-mapping)

### Indexing the data

By default Hibernate Search indexes are linked to the basic CRUD actions performed by Hibernate. Therefore you shouldn't need to manually index your domains as this will be
done automatically. However for convenience the plugin provides simple accessor methods into the HS indexing system.

Please see [Hibernate Search Indexing](https://docs.jboss.org/hibernate/search/6.0/reference/en-US/html_single/#mapper-orm-indexing) for information on the underlying system.

#### Rebuild index on start

Hibernate Search offers an option to rebuild the whole index using the MassIndexer API. This plugin provides a configuration which lets you to rebuild automatically your
indexes on startup.

To use the default options of the MassIndexer API, simply provide this option into your application.yml:

```yml
grails.plugins.hibernatesearch.rebuildIndexOnStart: true
```

If you need to tune the MassIndexer API, you could specify options with a map as following:

```yml
grails.plugins.hibernatesearch:
    rebuildIndexOnStart:
		batchSizeToLoadObjects: 30
		threadsForSubsequentFetching: 8 	
		threadsToLoadObjects: 4
		threadsForIndexWriter: 3
		cacheMode: NORMAL
```

#### Create index for existing data

The plugin lets you to create index of any indexed entity as following:

```groovy
MyDomainClass.search().createIndexAndWait()
```

This method relies on MassIndexer and can be configured like this:

```groovy
MyDomainClass.search().createIndexAndWait {
   ...
   batchSizeToLoadObjects 25
   cacheMode org.hibernate.CacheMode.NORMAL
   threadsToLoadObjects 5
   ...
}
```

#### Manual index changes

##### Adding instances to index

```groovy
// index only updated at commit time
MyDomainClass.search().withTransaction { transaction ->
   MyDomainClass.findAll().each {
      it.search().index()
   }
}
```

#### Deleting instances from index

```groovy
// index only updated at commit time
MyDomainClass.search().withTransaction { transaction ->
   MyDomainClass.get(3).search().purge()
}
```

To remove all entities of a given type, you could use the following purgeAll method:

```groovy
// index only updated at commit time
MyDomainClass.search().withTransaction {
    MyDomainClass.search().purgeAll()
}
```

## Search

The plugin provides you a dynamic method to search for indexed entities. This is done through
the [HibernateSearchApi](/src/main/groovy/grails/plugins/hibernate/search/HibernateSearchApi.groovy) class which opens a new Hibernate Search `SearchSession` for the domain
and then applies the closure to this object.

You can however open your own SearchSession at any time and query Hibernate Search using their
[query DSL](https://docs.jboss.org/hibernate/search/6.0/reference/en-US/html_single/#search-query-querydsl).

### Retrieving the results

All indexed domain classes provides ``.search()` method which lets you to list the results.

```groovy
class SomeController {

    def myAction = {MyCommand command ->

        def page = [max: Math.min(params.max ? params.int('max') : 10, 50), offset: params.offset ? params.int('offset') : 0]

      def myDomainClasses = MyDomainClass.search().list {

         if ( command.dateTo ) {
            below "publishedDate", command.dateTo
         }

         if ( command.dateFrom ) {
            above "publishedDate", command.dateFrom
         }

         mustNot {
            keyword "status", Status.DISABLED
         }

         if ( command.keyword ) {
            should {
               command.keyword.tokenize().each { keyword ->

                  def wild = keyword.toLowerCase() + '*'

                  wildcard "author", wild
                  wildcard "body", wild
                  wildcard "summary", wild
                  wildcard "title", wild
                  wildcard "categories.name", wild
               }
            }
         }

         sort "publishedDate", "asc"

         maxResults page.max

         offset page.offset
      }

      [myDomainClasses: myDomainClasses]
   }
}
```

### Performing SimpleQueryString searches

See [Hibernate Search Simple Query Strings](https://docs.jboss.org/hibernate/search/6.0/reference/en-US/html_single/#search-dsl-predicate-simple-query-string)
for more details on the actual query string. You can implement any other queries alongside a simple query string search.

#### Simple search on 1 field
```groovy
// Search for "war and peace or harmony" in the title field
def myDomainClasses = MyDomainClass.search().list {
	simpleQueryString 'war + (peace | harmony)', 'title'
}
```

#### Simple search on multiple fields
```groovy
// Search for "war and peace or harmony" in the title and description field
def myDomainClasses = MyDomainClass.search().list {
	simpleQueryString 'war + (peace | harmony)', 'title', 'description
}
```

#### Simple search on field setting AND as the default operator
```groovy
// Search for "war and peace" in the title field
def myDomainClasses = MyDomainClass.search().list {
	simpleQueryString 'war peace', [withAndAsDefaultOperator: true], 'title'
}
```

#### Simple search on multiple fields setting the boost for each field
```groovy
// Search for "war and peace" in the title field and description field with boosts applied
def myDomainClasses = MyDomainClass.search().list {
	simpleQueryString 'war + (peace | harmony)', ['title':2.0, 'description':0.5]
}
```

### Sorting the results

`sort()` method accepts an optional second parameter to specify the sort order: "asc"/"desc". Default is "asc".

Fields used for sorting can be analyzed, but must not be tokenized, so you should rather use normalizers on those fields.

If you try to sort on an indexed field which has not been marked as "sortable" you will either get warnings or full errors.
Therefore it is important to mark any indexed fields as sortable, and as sortable fields cannot be indexed with tokenizer analyzers you should also define a normalizer to be used (see the section on [Normalizer](#normalizer)s on how to define them).

```groovy
MyDomainClass.search().list {
   ...
   sort "publishedDate", "asc"
   ...  
}
```

If for some reasons, you want to sort results with a property which doesn't exist in your domain class, you should specify the sort type with a third parameter (default is String). You have three ways to achieve this:

#### By Specifying the type (could be Integer, String, Date, Double, Float, Long, Bigdecimal):

```groovy
MyDomainClass.search().list {
   ...
   sort "my_special_field", "asc", Integer
   ...
}
```

#### By Specifying directly its sort field (Lucene):

```groovy
def items = Item.search().list {
  ...
  sort "my_special_field", "asc", org.apache.lucene.search.SortField.Type.STRING_VAL
  ...
}
```

#### By specifying its sort field with string:

```groovy
def items = Item.search().list {
  ...
  sort "my_special_field", "asc", "string_val"
  ...
}
```

### Counting the results

You can also retrieve the number of results by using 'count' method:

```groovy
def myDomainClasses = MyDomainClass.search().count {
 ...
}
```

### Additional features

#### Support for ignoreAnalyzer(), ignoreFieldBridge() and boostedTo() functions

When searching for data, you may want to not use the field bridge or the analyzer. All methods (below, above, between, keyword, fuzzy) accept an optional map parameter to support this:

```groovy

MyDomainClass.search().list {

   keyword "status", Status.DISABLED, [ignoreAnalyzer: true]

   wildcard "description", "hellow*", [ignoreFieldBridge: true, boostedTo: 1.5f]

}
```

#### Fuzzy search

On fuzzy search, you can add an optional parameter to specify the max distance

See [Hibernate Fuzzy Search](https://docs.jboss.org/hibernate/stable/search/reference/en-US/html_single/#_fuzzy_queries)

```groovy

MyDomainClass.search().list {

   keyword "status", Status.DISABLED, [ignoreAnalyzer: true]

   fuzzy "description", "hellow", [ignoreFieldBridge: true, maxDistance: 2]

}
```

### Support for projections

Hibernate Search lets you to return only a subset of properties rather than the whole domain object. It makes it possible to avoid to query the database. This plugin supports this feature:

```groovy
def myDomainClasses = MyDomainClass.search().list {

    projection "author", "body"

}

myDomainClasses.each { result ->

    def author = result[0]
    def body  = result[1]

    ...
}

```

Don't forget to store the properties into the index as following:

```groovy
class MyDomainClass {

    [...]

    static luceneIndexing = {
        author index: 'yes', store: 'yes'
        body index: 'yes', store: 'yes'
    }
}
```

Note: If the projected field is a multi-valued field the value will be returned as a List instead of a String.

## Analysis

### Define named analyzers

Named analyzers are global and can be defined within runtime.groovy as following:

```groovy

import org.apache.solr.analysis.StandardTokenizerFactory
import org.apache.solr.analysis.LowerCaseFilterFactory
import org.apache.solr.analysis.NGramFilterFactory

...

grails.plugins.hibernatesearch = {

    analyzer( name: 'ngram', tokenizer: StandardTokenizerFactory ) {
        filter LowerCaseFilterFactory
        filter factory: NGramFilterFactory, params: [minGramSize: 3, maxGramSize: 3]
    }

}

```

This configuration is strictly equivalent to this annotation configuration:

```java
@AnalyzerDef(name = "ngram", tokenizer = @TokenizerDef(factory = StandardTokenizerFactory.class),
  filters = {
    @TokenFilterDef(factory = LowerCaseFilterFactory.class),
    @TokenFilterDef(factory = NGramFilterFactory.class,
      params = {
        @Parameter(name = "minGramSize",value = "3"),
        @Parameter(name = "maxGramSize",value = "3")
     })
})
public class Address {
...
}
```

### Use named analyzers

Set the analyzer at the entity level: all fields will be indexed with the analyzer

```groovy
class MyDomainClass {

    String author
    String body
    ...

    static luceneIndexing = {
        analyzer = 'ngram'
        author index: 'yes'
        body index: 'yes'
    }

}
```

Or set the analyzer at the field level:

```groovy
class MyDomainClass {

    String author
    String body
    ...

    static luceneIndexing = {
        author index: 'yes'
        body index: 'yes', analyzer: 'ngram'
        other index: 'yes', analyzer: new MyFilter()
    }

}
```

### Get scoped analyzer for given entity

The plugin lets you ro retrieve the scoped analyzer for a given analyzer with the search() method:

```groovy
def parser = new org.apache.lucene.queryParser.QueryParser (
    "title", Song.search().getAnalyzer() )
```

## Normalizer

Normalizers are analyzers without tokenization and are important for indexed fields which you want to sort,
see [Hibernate Search Normalizer](https://docs.jboss.org/hibernate/stable/search/reference/en-US/html_single/#section-normalizers) for more information.

### Define named normalizers

Named normalizers are global and can be defined within runtime.groovy as following:

```groovy

import org.apache.lucene.analysis.core.LowerCaseFilterFactory
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilterFactory

...

grails.plugins.hibernatesearch = {

    normalizer(name: 'lowercase') {
        filter ASCIIFoldingFilterFactory
        filter LowerCaseFilterFactory
    }

}

```

This configuration is strictly equivalent to this annotation configuration:

```java
@NormalizerDef(name = "lowercase",
  filters = {
    @TokenFilterDef(factory = ASCIIFoldingFilterFactory.class),
    @TokenFilterDef(factory = LowerCaseFilterFactory.class)
})
public class Address {
...
}
```

Another way to define a normalizer when using Lucene as the backend is to create a class implementing LuceneAnalysisConfigurer and progrmatically create the normalizer.

```groovy
package com.example.lucene

class MyLuceneAnalysisConfigurer implements LuceneAnalysisConfigurer {

    @Override
    void configure(LuceneAnalysisConfigurationContext context) {
        context.normalizer( "lowercase" ).custom()
                .tokenFilter( LowerCaseFilterFactory.class )
                .tokenFilter( ASCIIFoldingFilterFactory.class );
    }
}
```
and in application.groovy (or application.yml):
```yml
hibernate.search.backend.analysis.configurer = 'class:com.example.lucene.MyLuceneAnalysisConfigurer'
```

### Use named normalizer

Set the normalizer at the field level

```groovy
class MyDomainClass {

    String author
    String body
    ...

    static luceneIndexing = {
        author index: 'yes', sortable: [name: author_sort, normalizer: 'lowercase']
        body index: 'yes', sortable: [name: author_sort, normalizer: LowerCaseFilterFactory]
    }

}
```

## Filters

In Hibernate Search 5.9.x the `Filter` class is completely removed and filters must now be applied as
[Full-Text Filters](https://docs.jboss.org/hibernate/stable/search/reference/en-US/html_single/#query-filter-fulltext)
which are passed Querys rather than Filters.

### Define named filters

Named filters are global and MUST be defined within runtime.groovy as following:

```groovy

...

grails.plugins.hibernatesearch = {

    // cf official doc https://docs.jboss.org/hibernate/stable/search/reference/en-US/html_single/#query-filter-fulltext
    // Example 116. Defining and implementing a Filter
    fullTextFilter name: "bestDriver", impl: BestDriversFilter

    // cf official doc https://docs.jboss.org/hibernate/stable/search/reference/en-US/html_single/#query-filter-fulltext
    // Example 118. Using parameters in the actual filter implementation    
    fullTextFilter name: "security", impl: SecurityFilterFactory, cache: "instance_only"

}

```

If they are not defined in runtime.groovy they will not be available for querying.

### Filter query results

Filter query results looks like this:


MyDomainClass.search().list {


```groovy

// without params:
MyDomainClass.search().list {
  ...
  filter "bestDriver"
  ...
}

// with params:
MyDomainClass.search().list {
  ...
   filter name: "security", params: [ level: 4 ]
  ...
}

```

## Options

```groovy
grails.plugins.hibernatesearch = {
	rebuildIndexOnStart false // see related section above
	throwOnEmptyQuery false // throw or not exception when Hibernate Search raises an EmptyQueryException
	fullTextFilter /* ... */ // see related section above
}
```

## Migrating from v2.x to v3.x

There is a significant change between Hibernate Search 5 and Hibernate Search 6. To start with HS6 has a much more powerful and friendly programmatic API and query DSL, they
have also separated the "backend" out from the core search engine. This is advantageous as it means you are no longer tied to Lucene when searching, all you need to do now is
apply your chosen backend dependency and it wires into the Hibernate Search engine, this will allow you to swop between Lucene or Elasticsearch with ease.

However they have also moved a lot of configuration around, and also deprecated or renamed a lot of the methods. Every effort has been made to handle migration automatically
for you however there are some properties or configurations which no longer have a direct comparison. Log warnings have been applied to every location where possible, prefixed
with `DEPRECATED`, that either something has been moved or removed. Where something has been moved or renamed we have done the migration where possible for you, you will still
receive the warnings until you update your code but it will work. Where options are no longer allowed we still warn and ignore your settings.

To start we would recommend applying the following dependency, this will bring in the Lucene backend along with some implementing classes which warn that methods are
deprecated. This will help if you have any custom written code, or custom ClassBridges as it will allow the code to compile with deprecation warnings.

```groovy
compile "org.hibernate.search:hibernate-search-v5migrationhelper-orm:6.0.8.OXBRC"
```

You will want to choose the backend you wish to use, if you used this plugin before with no additional changes then you will want the Lucene backend, but you may wish to take
this opportunity to swop to Elasticsearch. Whatever your choice you will need to apply one of the below dependencies, as we do not apply either in our build.

* [Lucene](https://lucene.apache.org/) : `org.hibernate.search:hibernate-search-backend-lucene:6.0.8.OXBRC`
* [Elasticsearch](https://www.elastic.co/guide/en/elasticsearch/reference/7.10) : `org.hibernate.search:hibernate-search-backend-elasticsearch:6.0.8.OXBRC`

We would also recommend reading the Hibernate Search [Migration Guide](https://docs.jboss.org/hibernate/search/6.0/migration/html_single/#_introduction)
especially the section around [configuration changes](https://docs.jboss.org/hibernate/search/6.0/migration/html_single/#configuration), here they list all the configuration
keys which have been moved and where they've been moved to.

The following sections document any changes required to fix the deprecation warnings.

### Mapping

The following property attributes have been removed from HS6

* containedIn
* numeric
* boost
* date

The following property attributes have been renamed or functionally changed

`index`
: Renamed to `searchable`, the possible values remain the same

`store`
: Rename to `projectable`, the possible values remain the same

`bridge`
: This now expects a Class which extends ValueBridge. The Bridging API has changed a lot, see [bridges](#bridges) for how to do this in Grails
and [migrating bridges](https://docs.jboss.org/hibernate/search/6.0/migration/html_single/#bridges) for further information with respect to HS6.

`analyzer`
: This now expects a string naming a defined analyzer. See [analyzers](#analyzers) for how to do this in the plugin and
[migrating analzyers](https://docs.jboss.org/hibernate/search/6.0/migration/html_single/#analyzer) for further information with respect to HS6.

`normalizer`
: This now expects a string naming a defined normalizer. See [normalizers](#normalizers) for how to do this in the plugin and
[migrating normalizers](https://docs.jboss.org/hibernate/search/6.0/migration/html_single/#normalizer) for further information with respect to HS6.

### Searching

## Notes

### IDE Integration

Unfortunately IDEs will not recognise the `search()` method as it is added dynamically.
One messy but possible way to get around this and gain access to the DSL inside the IDE is to
add an extra static method to your class.
This is not ideal but it may make your programming easier.

```groovy
class DomainClass {

    ...

    static List<DomainClass> hibernateSearchList(@DelegatesTo(HibernateSearchApi) Closure closure){
        DomainClass.search().list(closure)
    }
    
    static int hibernateSearchCount(@DelegatesTo(HibernateSearchApi) Closure closure){
        DomainClass.search().count(closure)
    }
}
```

### SessionFactory failures during startup

During the SessionFactory build process any exceptions which occur during the HibernateSearch boot sequence
are silently wrapped and hidden inside the futures.
This means there will be a particularly helpful exception thrown :

```
Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'methodValidationPostProcessor' defined in class path resource [org/springframework/boot/autoconfigure/validation/ValidationAutoConfiguration.class]: Unsatisfied dependency expressed through method 'methodValidationPostProcessor' parameter 0; nested exception is org.springframework.beans.factory.BeanCreationException: Error creating bean with name 'hibernateDatastoreServiceRegistry': Cannot resolve reference to bean 'hibernateDatastore' while setting constructor argument; nested exception is org.springframework.beans.factory.BeanCreationException: Error creating bean with name 'hibernateDatastore': Bean instantiation via constructor failed; nested exception is org.springframework.beans.BeanInstantiationException: Failed to instantiate [org.grails.orm.hibernate.HibernateDatastore]: Constructor threw exception; nested exception is java.lang.NullPointerException
```

which will stacktrace down to :

```
Caused by: java.lang.NullPointerException: null
	at org.grails.orm.hibernate.cfg.HibernateMappingContextConfiguration$2.sessionFactoryClosed(HibernateMappingContextConfiguration.java:266)
	at org.hibernate.internal.SessionFactoryObserverChain.sessionFactoryClosed(SessionFactoryObserverChain.java:61)
	at org.hibernate.internal.SessionFactoryImpl.close(SessionFactoryImpl.java:756)
	at org.hibernate.search.hcore.impl.HibernateSearchSessionFactoryObserver.boot(HibernateSearchSessionFactoryObserver.java:134)
	at org.hibernate.search.hcore.impl.HibernateSearchSessionFactoryObserver.sessionFactoryCreated(HibernateSearchSessionFactoryObserver.java:79)
	at org.hibernate.internal.SessionFactoryObserverChain.sessionFactoryCreated(SessionFactoryObserverChain.java:35)
	at org.hibernate.internal.SessionFactoryImpl.<init>(SessionFactoryImpl.java:366)
	at org.hibernate.boot.internal.SessionFactoryBuilderImpl.build(SessionFactoryBuilderImpl.java:452)
	at org.hibernate.cfg.Configuration.buildSessionFactory(Configuration.java:710)
	at org.grails.orm.hibernate.cfg.HibernateMappingContextConfiguration.buildSessionFactory(HibernateMappingContextConfiguration.java:274)
	at grails.plugins.hibernate.search.context.HibernateSearchMappingContextConfiguration.buildSessionFactory(HibernateSearchMappingContextConfiguration.java:357)
	at org.grails.orm.hibernate.connections.HibernateConnectionSourceFactory.create(HibernateConnectionSourceFactory.java:86)
	at org.grails.orm.hibernate.connections.AbstractHibernateConnectionSourceFactory.create(AbstractHibernateConnectionSourceFactory.java:39)
	at org.grails.orm.hibernate.connections.AbstractHibernateConnectionSourceFactory.create(AbstractHibernateConnectionSourceFactory.java:23)
	at org.grails.datastore.mapping.core.connections.AbstractConnectionSourceFactory.create(AbstractConnectionSourceFactory.java:64)
	at org.grails.datastore.mapping.core.connections.AbstractConnectionSourceFactory.create(AbstractConnectionSourceFactory.java:52)
	at org.grails.datastore.mapping.core.connections.ConnectionSourcesInitializer.create(ConnectionSourcesInitializer.groovy:24)
	at org.grails.orm.hibernate.HibernateDatastore.<init>(HibernateDatastore.java:196)
	at sun.reflect.NativeConstructorAccessorImpl.newInstance0(Native Method)
	at sun.reflect.NativeConstructorAccessorImpl.newInstance(NativeConstructorAccessorImpl.java:62)
	at sun.reflect.DelegatingConstructorAccessorImpl.newInstance(DelegatingConstructorAccessorImpl.java:45)
	at java.lang.reflect.Constructor.newInstance(Constructor.java:423)
	at org.springsource.loaded.ri.ReflectiveInterceptor.jlrConstructorNewInstance(ReflectiveInterceptor.java:1076)
	at org.springframework.beans.BeanUtils.instantiateClass(BeanUtils.java:142)
	... 51 common frames omitted
```

The actual exception stack is not at all helpful as whilst the actual failure point is in:
```
org.hibernate.search.hcore.impl.HibernateSearchSessionFactoryObserver.boot(HibernateSearchSessionFactoryObserver.java:134)
```

It is masked inside the catch statement at line 127 inside the class, 
as the finally clause is what results in the above exception stacktrace. 

```java
public class HibernateSearchSessionFactoryObserver implements SessionFactoryObserver {
    // ...
    
    private synchronized void boot(SessionFactory factory) {
        try{ 
            // ...
        }
        catch (Throwable t) {
            extendedSearchIntegratorFuture.completeExceptionally( t );
            // This will make the SessionFactory abort and close itself
            throw t;
        }finally {
            if ( failedBoot ) {
                factory.close();
            }
        }
    }
    
    // ...
}
```

Therefore if you get the above exceptions then drop a debug point at line 130 and then start with a debugger running. 
The debug point will give you the helpful exception as to why the boot has failed.

## Examples

A sample project is available at this repository URL
https://github.com/lgrignon/grails3-quick-start

It contains several branches for each version of this plugin

## Change log

### v3.0

* Grails 4.0.x
* GORM 7.0.4
* Hibernate 5.4.4
* Hibernate Search 6.0.8

### v2.3

* Grails 3.3.x
* GORM 6.1
* Hibernate 5.2.10
* Hibernate Search 5.9.1
* Add sortable field
* Add SimpleQueryString

### v2.2
* Grails 3.3.x
* GORM 6.1
* Hibernate 5.2.9
* Hibernate Search 5.7

### v2.1.2
* Supports hibernate.configClass if any
* Removed dependencies to info.app.grailsVersion, info.app.name

### v2.1
* Grails 3.2.x
* GORM 6
* Hibernate 5.2.9
* Hibernate Search 5.7

### v2.0.2
Support for indexing trait properties

### v2.0.1
Support for indexing inherited properties

### v2.0
* Grails 3.1.x
* GORM 5
* Hibernate 5.1.1
* Hibernate Search 5.5.4

### v1.x
* Grails 2.x
* Hibernate 4

## Authors

**Mathieu Perez**

+ http://twitter.com/mathieuperez

**Julie Ingignoli**

+ http://twitter.com/ZeJulie

**Louis Grignon**

+ https://github.com/lgrignon

## Development / Contribution

Install with:
```
gradlew clean publishToMavenLocal
```


Publish with:
```
gradlew clean bintrayUpload --stacktrace -PbintrayUser=... -PbintrayKey=...
```

## License

Licensed under the Apache License, Version 2.0: http://www.apache.org/licenses/LICENSE-2.0
