/*
 * Copyright 2014 The Apache Software Foundation.
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
package org.apache.usergrid.corepersistence;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.usergrid.corepersistence.index.IndexLocationStrategyFactory;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.commons.lang3.RandomStringUtils;

import org.apache.usergrid.AbstractCoreIT;
import org.apache.usergrid.cassandra.SpringResource;
import org.apache.usergrid.corepersistence.util.CpNamingUtils;
import org.apache.usergrid.persistence.Entity;
import org.apache.usergrid.persistence.EntityManager;
import org.apache.usergrid.persistence.EntityRef;
import org.apache.usergrid.persistence.Results;
import org.apache.usergrid.persistence.collection.EntityCollectionManager;
import org.apache.usergrid.persistence.collection.EntityCollectionManagerFactory;
import org.apache.usergrid.persistence.core.scope.ApplicationScope;
import org.apache.usergrid.persistence.core.scope.ApplicationScopeImpl;
import org.apache.usergrid.persistence.index.EntityIndex;
import org.apache.usergrid.persistence.index.CandidateResults;
import org.apache.usergrid.persistence.index.EntityIndexFactory;
import org.apache.usergrid.persistence.index.SearchEdge;
import org.apache.usergrid.persistence.index.SearchTypes;
import org.apache.usergrid.persistence.Query;
import org.apache.usergrid.persistence.model.entity.Id;
import org.apache.usergrid.persistence.model.entity.SimpleId;

import com.fasterxml.uuid.UUIDComparator;
import com.google.inject.Injector;

import net.jcip.annotations.NotThreadSafe;

import static org.apache.usergrid.persistence.Schema.TYPE_APPLICATION;
import static org.apache.usergrid.persistence.core.util.IdGenerator.createId;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;


/**
 * Test on read style clean-up of stale ElasticSearch indexes.
 */
@NotThreadSafe
@Ignore
public class StaleIndexCleanupTest extends AbstractCoreIT {
    private static final Logger logger = LoggerFactory.getLogger( StaleIndexCleanupTest.class );
    public static final String EVENTS_DISABLED = "corepersistence.events.disabled";

    // take it easy on Cassandra
    private static final long writeDelayMs = 0;

    Lock sequential = new ReentrantLock();

    @Before
    public void before() {
        // if tests run in parallel there will likely be a conflict over the allow.stale.entities
        sequential.lock();
    }

    @After
    public void after() {
        System.clearProperty( EVENTS_DISABLED );
    }

    /**
     * Test that updating an entity causes the entity's version number to change.
     */
    @Test
    public void testUpdateVersioning() throws Exception {

        // turn off post processing stuff that cleans up stale entities
        System.setProperty(EVENTS_DISABLED, "true");

        final EntityManager em = app.getEntityManager();

        Entity thing = em.create("thing", new HashMap<String, Object>() {{
            put("name", "thing1");
        }});
        app.waitForQueueDrainAndRefreshIndex();

        Thread.sleep(1000);
        assertEquals(1, queryCollectionCp("things", "thing", "select *").size());

        org.apache.usergrid.persistence.model.entity.Entity cpEntity = getCpEntity(thing);
        UUID oldVersion = cpEntity.getVersion();

        em.updateProperties(thing, new HashMap<String, Object>() {{
            put("stuff", "widget");
        }});
        app.waitForQueueDrainAndRefreshIndex();
        Thread.sleep(1000);

        org.apache.usergrid.persistence.model.entity.Entity cpUpdated = getCpEntity(thing);
        assertEquals("widget", cpUpdated.getField("stuff").getValue());
        UUID newVersion = cpUpdated.getVersion();

        assertTrue("New version is greater than old",
            UUIDComparator.staticCompare(newVersion, oldVersion) > 0);

        CandidateResults results;
        results = queryCollectionCp("things", "thing", "select *");
        assertEquals(1, results.size());

        assertEquals(newVersion, results.get(0).getVersion());

    }


    /**
     * Test that the EntityDeleteImpl cleans up stale indexes on delete. Ensures that when an
     * entity is deleted its old indexes are cleared from ElasticSearch.
     */
    @Test
    public void testCleanupOnDelete() throws Exception {

        logger.info("Started testStaleIndexCleanup()");

        // turn off post processing stuff that cleans up stale entities
        System.setProperty( EVENTS_DISABLED, "true" );

        final EntityManager em = app.getEntityManager();

        final int numEntities = 5;
        final int numUpdates = 5;

        // create lots of entities
        final List<Entity> things = new ArrayList<Entity>(numEntities);
        for ( int i=0; i<numEntities; i++) {
            final String thingName = "thing" + i;
            things.add( em.create("thing", new HashMap<String, Object>() {{
                put("name", thingName);
            }}));
            Thread.sleep( writeDelayMs );
        }
        app.waitForQueueDrainAndRefreshIndex();

        CandidateResults crs = queryCollectionCp( "things", "thing", "select *");
        Assert.assertEquals( "Expect no stale candidates yet", numEntities, crs.size() );

        // update each one a bunch of times
        int count = 0;

        List<Entity> maxVersions = new ArrayList<>(numEntities);

        for ( Entity thing : things ) {
            Entity toUpdate = null;

            for ( int j=0; j<numUpdates; j++) {
                toUpdate = em.get( thing.getUuid() );
                toUpdate.setProperty( "property"  + j, UUID.randomUUID().toString());

                em.update(toUpdate);

                count++;
                if ( count % 100 == 0 ) {
                    logger.info("Updated {} of {} times", count, numEntities * numUpdates);
                }
            }

            maxVersions.add( toUpdate );
        }
        em.refreshIndex();

        // query Core Persistence directly for total number of result candidates
        for(int i = 0;i<10;i++){

            crs = queryCollectionCp("things", "thing", "select *");
            if(numEntities * (numUpdates + 1) == crs.size()){
                break;
            }else{
                Thread.sleep(1100);
            }
        }

//        Assert.assertEquals("Expect stale candidates", numEntities * (numUpdates + 1), crs.size());

        // turn ON post processing stuff that cleans up stale entities
        System.setProperty(EVENTS_DISABLED, "false");


        Thread.sleep(250); // delete happens asynchronously, wait for some time

        //refresh the app index
        app.waitForQueueDrainAndRefreshIndex();

        Thread.sleep(250); // refresh happens asynchronously, wait for some time


        //we can't use our candidate result sets here.  The repair won't happen since we now have orphaned documents in our index
        //us the EM so the repair process happens

        Results results = null;
        count = 0;
        do {
            //trigger the repair
            results = queryCollectionEm("things", "select *");
            results.getEntities().stream().forEach(entity -> {
               try {
                   em.delete(entity);
               }catch (Exception e){
                   //
               }
            });
            //refresh the app index
            app.waitForQueueDrainAndRefreshIndex();

            crs = queryCollectionCp("things", "thing", "select *");

        } while ( crs.size() > 0 && count++ < 2000 );

        Assert.assertEquals( "Expect no candidates", 0, crs.size() );
    }


    /**
     * Test that the AbstractElasticsearchFilter de-indexes old versions when reading candidates
     */
    @Test()
    public void testCleanupOnUpdate() throws Exception {

        logger.info( "Started testCleanupOnUpdate()" );

        // turn off post processing stuff that cleans up stale entities
        System.setProperty( EVENTS_DISABLED, "true" );

        final EntityManager em = app.getEntityManager();

        final int numEntities = 10;
        final int numUpdates = 5;

        // create lots of entities
        final List<Entity> dogs = new ArrayList<Entity>(numEntities);
        for ( int i=0; i<numEntities; i++) {
            final String dogName = "dog" + i;
            dogs.add(em.create("dog", new HashMap<String, Object>() {{
                put("name", dogName);
            }}));
        }
        app.waitForQueueDrainAndRefreshIndex();

        CandidateResults crs = queryCollectionCp( "dogs", "dog", "select *");
        Assert.assertEquals("Expect no stale candidates yet", numEntities, crs.size());

        // turn off post processing stuff that cleans up stale entities

        // update each entity a bunch of times

        int count = 0;
        for ( Entity dog : dogs ) {

            for ( int j=0; j<numUpdates; j++) {
                Entity toUpdate = em.get( dog.getUuid() );
                toUpdate.setProperty( "property", RandomStringUtils.randomAlphanumeric(10));
                em.update(toUpdate);
                count++;
                if ( count % 100 == 0 ) {
                    logger.info("Updated {} of {} times", count, numEntities * numUpdates);
                }
            }

        }
        app.waitForQueueDrainAndRefreshIndex();

        // wait for indexes to be cleared for the deleted entities
        count = 0;

        do {
            //trigger the repair
            queryCollectionEm("dogs", "select * order by created");
            app.waitForQueueDrainAndRefreshIndex();
            crs = queryCollectionCp("dogs", "dog", "select *");
        } while ( crs.size() != numEntities && count++ < 15 );

        Assert.assertEquals("Expect candidates without earlier stale entities", numEntities,crs.size());
    }


    /**
    /**
     * Go around EntityManager and get directly from Core Persistence.
     */
    private org.apache.usergrid.persistence.model.entity.Entity getCpEntity( EntityRef eref ) {

        EntityManager em = app.getEntityManager();


        EntityCollectionManagerFactory ecmf =
            SpringResource.getInstance().getBean( Injector.class ).getInstance( EntityCollectionManagerFactory.class );

        EntityCollectionManager ecm = ecmf.createCollectionManager( new ApplicationScopeImpl( new SimpleId(em.getApplicationId(),  "application" ) ) );

        return ecm.load( new SimpleId( eref.getUuid(), eref.getType() ) )
                .toBlocking().lastOrDefault( null );
    }


    /**
     * Go around EntityManager and execute query directly against Core Persistence.
     * Results may include stale index entries.
     */
    private CandidateResults queryCollectionCp(
            final String collName, final String type, final String query ) {

        EntityManager em = app.getEntityManager();

        EntityIndexFactory eif =  SpringResource.getInstance().getBean( Injector.class ).getInstance(
            EntityIndexFactory.class );

        ApplicationScope as = new ApplicationScopeImpl(
            new SimpleId( em.getApplicationId(), TYPE_APPLICATION ) );
        IndexLocationStrategyFactory indexLocationStrategyFactory = SpringResource.getInstance().getBean( Injector.class ).getInstance(IndexLocationStrategyFactory.class);
        EntityIndex ei = eif.createEntityIndex(indexLocationStrategyFactory.getIndexLocationStrategy(as));

        final Id rootId = createId(em.getApplicationId(), TYPE_APPLICATION);
        SearchEdge is = CpNamingUtils.createCollectionSearchEdge( rootId, collName );


        return ei.search( is, SearchTypes.fromTypes( type ), query, 1000, 0, false );
    }

    /**
        * Go around EntityManager and execute query directly against Core Persistence.
        * Results may include stale index entries.
        */
       private Results queryCollectionEm( final String collName,  final String query ) throws Exception {

           EntityManager em = app.getEntityManager();


           final Results results = em.searchCollection( em.getApplicationRef(), collName, Query.fromQL( query ).withLimit( 10000 ) );

           return results;
       }
}
