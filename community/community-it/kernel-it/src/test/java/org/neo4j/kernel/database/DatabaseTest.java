/*
 * Copyright (c) 2002-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.database;

import org.eclipse.collections.api.set.ImmutableSet;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.io.IOException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.neo4j.collection.Dependencies;
import org.neo4j.configuration.Config;
import org.neo4j.internal.id.IdGeneratorFactory;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.layout.DatabaseLayout;
import org.neo4j.io.pagecache.DelegatingPageCache;
import org.neo4j.io.pagecache.IOLimiter;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.io.pagecache.PagedFile;
import org.neo4j.io.pagecache.tracing.DefaultPageCacheTracer;
import org.neo4j.io.pagecache.tracing.cursor.context.VersionContextSupplier;
import org.neo4j.kernel.lifecycle.LifecycleException;
import org.neo4j.kernel.monitoring.DatabasePanicEventGenerator;
import org.neo4j.logging.AssertableLogProvider;
import org.neo4j.logging.NullLogProvider;
import org.neo4j.logging.internal.DatabaseLogService;
import org.neo4j.logging.internal.LogService;
import org.neo4j.logging.internal.SimpleLogService;
import org.neo4j.monitoring.DatabaseHealth;
import org.neo4j.monitoring.Health;
import org.neo4j.test.rule.DatabaseRule;
import org.neo4j.test.rule.PageCacheConfig;
import org.neo4j.test.rule.PageCacheRule;
import org.neo4j.test.rule.TestDirectory;
import org.neo4j.test.rule.fs.DefaultFileSystemRule;

import static org.apache.commons.lang3.exception.ExceptionUtils.getRootCause;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.neo4j.configuration.GraphDatabaseSettings.DEFAULT_DATABASE_NAME;
import static org.neo4j.configuration.GraphDatabaseSettings.neo4j_home;
import static org.neo4j.configuration.GraphDatabaseSettings.transaction_logs_root_path;
import static org.neo4j.kernel.database.DatabaseFileHelper.filesToKeepOnTruncation;
import static org.neo4j.logging.AssertableLogProvider.Level.WARN;
import static org.neo4j.logging.LogAssertions.assertThat;

public class DatabaseTest
{
    @Rule
    public DefaultFileSystemRule fs = new DefaultFileSystemRule();
    @Rule
    public TestDirectory directory = TestDirectory.testDirectory( fs.get() );
    @Rule
    public DatabaseRule databaseRule = new DatabaseRule();
    @Rule
    public PageCacheRule pageCacheRule = new PageCacheRule();

    private DatabaseLayout databaseLayout;

    @Before
    public void setUp()
    {
        databaseLayout = DatabaseLayout.ofFlat( directory.directoryPath( DEFAULT_DATABASE_NAME ) );
    }

    @Test
    public void databaseHealthShouldBeHealedOnStart() throws Throwable
    {
        Database database = null;
        try
        {
            Health databaseHealth = new DatabaseHealth( mock( DatabasePanicEventGenerator.class ),
                    NullLogProvider.getInstance().getLog( DatabaseHealth.class ) );
            Dependencies dependencies = new Dependencies();
            dependencies.satisfyDependency( databaseHealth );

            database = databaseRule.getDatabase( databaseLayout, fs.get(), pageCacheRule.getPageCache( fs.get() ),
                    dependencies );

            databaseHealth.panic( new Throwable() );

            database.start();

            databaseHealth.assertHealthy( Throwable.class );
        }
        finally
        {
            if ( database != null )
            {
                database.stop();
            }
        }
    }

    @Test
    public void dropDataOfNotStartedDatabase()
    {
        File customTxLogsRoot = directory.directory( "customTxLogs" );
        Config cfg = Config.newBuilder().set( neo4j_home, directory.homeDir().toPath() ).set( transaction_logs_root_path, customTxLogsRoot.toPath() ).build();
        DatabaseLayout databaseLayout = DatabaseLayout.of( cfg );
        Database database = databaseRule.getDatabase( databaseLayout, fs.get(), pageCacheRule.getPageCache( fs.get() ) );

        database.start();
        database.stop();

        assertNotEquals( databaseLayout.databaseDirectory(), databaseLayout.getTransactionLogsDirectory() );
        assertTrue( fs.fileExists( databaseLayout.databaseDirectory().toFile() ) );
        assertTrue( fs.fileExists( databaseLayout.getTransactionLogsDirectory().toFile() ) );

        database.drop();

        assertFalse( fs.fileExists( databaseLayout.databaseDirectory().toFile() ) );
        assertFalse( fs.fileExists( databaseLayout.getTransactionLogsDirectory().toFile() ) );
    }

    @Test
    public void truncateNotStartedDatabase()
    {
        File customTxLogsRoot = directory.directory( "truncateCustomTxLogs" );
        Config cfg = Config.newBuilder().set( neo4j_home, directory.homeDir().toPath() ).set( transaction_logs_root_path, customTxLogsRoot.toPath() ).build();
        DatabaseLayout databaseLayout = DatabaseLayout.of( cfg );
        Database database = databaseRule.getDatabase( databaseLayout, fs, pageCacheRule.getPageCache( fs ) );

        database.start();
        database.stop();

        File databaseDirectory = databaseLayout.databaseDirectory().toFile();
        File transactionLogsDirectory = databaseLayout.getTransactionLogsDirectory().toFile();
        assertTrue( fs.fileExists( databaseDirectory ) );
        assertTrue( fs.fileExists( transactionLogsDirectory ) );

        File[] databaseFilesShouldExist = filesToKeepOnTruncation( databaseLayout ).stream().map( Path::toFile ).filter( File::exists ).toArray( File[]::new );

        database.truncate();

        assertTrue( fs.fileExists( databaseDirectory ) );
        assertTrue( fs.fileExists( transactionLogsDirectory ) );
        File[] currentDatabaseFiles = databaseDirectory.listFiles();
        assertThat( currentDatabaseFiles ).contains( databaseFilesShouldExist );
    }

    @Test
    public void doNotFlushDataFilesOnDatabaseTruncate()
    {
        FilesCollectionPageCache pageCache = new FilesCollectionPageCache( pageCacheRule, fs );
        Database database = databaseRule.getDatabase( databaseLayout, fs.get(), pageCache );

        database.start();
        database.truncate();

        List<File> removedFiles = pageCache.getPagedFiles().stream().filter( PagedFile::isDeleteOnClose )
                .map( PagedFile::file )
                .collect( Collectors.toList() );

        DatabaseLayout databaseLayout = database.getDatabaseLayout();
        Set<Path> files = databaseLayout.storeFiles();
        files.removeAll( filesToKeepOnTruncation( databaseLayout ) );
        File[] filesShouldBeDeleted = files.stream().map( Path::toFile ).filter( File::exists ).toArray( File[]::new );
        assertThat( removedFiles ).contains( filesShouldBeDeleted );
    }

    @Test
    public void filesRecreatedAfterTruncate()
    {
        File customTxLogsRoot = directory.directory( "truncateCustomTxLogs" );
        Config cfg = Config.newBuilder().set( neo4j_home, directory.homeDir().toPath() ).set( transaction_logs_root_path, customTxLogsRoot.toPath() ).build();
        DatabaseLayout databaseLayout = DatabaseLayout.of( cfg );
        Database database = databaseRule.getDatabase( databaseLayout, fs.get(), pageCacheRule.getPageCache( fs.get() ) );

        database.start();

        File databaseDirectory = databaseLayout.databaseDirectory().toFile();
        File transactionLogsDirectory = databaseLayout.getTransactionLogsDirectory().toFile();
        assertTrue( fs.fileExists( databaseDirectory ) );
        assertTrue( fs.fileExists( transactionLogsDirectory ) );

        File[] databaseFilesBeforeTruncate = fs.listFiles( databaseDirectory );
        File[] logFilesBeforeTruncate = fs.listFiles( transactionLogsDirectory );

        database.truncate();

        assertTrue( fs.fileExists( databaseDirectory ) );
        assertTrue( fs.fileExists( transactionLogsDirectory ) );

        File[] databaseFiles = fs.listFiles( databaseDirectory );
        File[] logFiles = fs.listFiles( transactionLogsDirectory );

        // files are equal by name - every store file is recreated as result
        assertThat( databaseFilesBeforeTruncate ).contains( databaseFiles );
        assertThat( logFilesBeforeTruncate ).contains( logFiles );
    }

    @Test
    public void noPageCacheFlushOnDatabaseDrop() throws Throwable
    {
        DefaultPageCacheTracer pageCacheTracer = new DefaultPageCacheTracer();
        PageCacheConfig pageCacheConfig = PageCacheConfig.config().withTracer( pageCacheTracer );
        PageCache pageCache = spy( pageCacheRule.getPageCache( fs.get(), pageCacheConfig ) );
        Database database = databaseRule.getDatabase( databaseLayout, fs.get(), pageCache );

        database.start();
        verify( pageCache, never() ).flushAndForce();
        verify( pageCache, never() ).flushAndForce( any( IOLimiter.class ) );

        long flushesBeforeClose = pageCacheTracer.flushes();

        database.drop();

        assertEquals( flushesBeforeClose, pageCacheTracer.flushes() );
    }

    @Test
    public void removeDatabaseDataAndLogsOnDrop()
    {
        File customTxLogsRoot = directory.directory( "customTxLogs" );
        Config cfg = Config.newBuilder().set( neo4j_home, directory.homeDir().toPath() ).set( transaction_logs_root_path, customTxLogsRoot.toPath() ).build();
        DatabaseLayout databaseLayout = DatabaseLayout.of( cfg );
        Database database = databaseRule.getDatabase( databaseLayout, fs.get(), pageCacheRule.getPageCache( fs.get() ) );

        database.start();

        assertNotEquals( databaseLayout.databaseDirectory(), databaseLayout.getTransactionLogsDirectory() );
        assertTrue( fs.fileExists( databaseLayout.databaseDirectory().toFile() ) );
        assertTrue( fs.fileExists( databaseLayout.getTransactionLogsDirectory().toFile() ) );

        database.drop();

        assertFalse( fs.fileExists( databaseLayout.databaseDirectory().toFile() ) );
        assertFalse( fs.fileExists( databaseLayout.getTransactionLogsDirectory().toFile() ) );
    }

    @Test
    public void flushDatabaseDataOnStop() throws Throwable
    {
        DefaultPageCacheTracer pageCacheTracer = new DefaultPageCacheTracer();
        PageCacheConfig pageCacheConfig = PageCacheConfig.config().withTracer( pageCacheTracer );
        PageCache pageCache = spy( pageCacheRule.getPageCache( fs.get(), pageCacheConfig ) );
        Database database = databaseRule.getDatabase( databaseLayout, fs.get(), pageCache );

        database.start();
        verify( pageCache, never() ).flushAndForce();
        verify( pageCache, never() ).flushAndForce( any( IOLimiter.class ) );

        long flushesBeforeClose = pageCacheTracer.flushes();

        database.stop();

        assertNotEquals( flushesBeforeClose, pageCacheTracer.flushes() );
    }

    @Test
    public void flushOfThePageCacheHappensOnlyOnceDuringShutdown() throws Throwable
    {
        PageCache realPageCache = pageCacheRule.getPageCache( fs.get() );
        List<PagedFile> files = new ArrayList<>();
        PageCache pageCache = spy( realPageCache );
        doAnswer( (Answer<PagedFile>) invocation ->
        {
            PagedFile file = spy( realPageCache.map( invocation.getArgument( 0, File.class ),
                                                     invocation.getArgument( 1, VersionContextSupplier.class ),
                                                     invocation.getArgument( 2, Integer.class ),
                                                     invocation.getArgument( 3, ImmutableSet.class ) ) );
            files.add( file );
            return file;
        } )
        .when( pageCache ).map( any( File.class ), any( VersionContextSupplier.class ), anyInt(), any() );

        Database database = databaseRule.getDatabase( databaseLayout, fs.get(), pageCache );
        files.clear();

        database.start();
        verify( pageCache, never() ).flushAndForce();
        verify( pageCache, never() ).flushAndForce( any( IOLimiter.class ) );

        database.stop();

        verify( pageCache, never() ).flushAndForce( IOLimiter.UNLIMITED );
        for ( PagedFile file : files )
        {
            verify( file, atLeastOnce() ).flushAndForce( IOLimiter.UNLIMITED );
        }
    }

    @Test
    public void flushOfThePageCacheOnShutdownHappensIfTheDbIsHealthy() throws Throwable
    {
        PageCache realPageCache = pageCacheRule.getPageCache( fs.get() );
        List<PagedFile> files = new ArrayList<>();
        PageCache pageCache = spy( realPageCache );
        doAnswer( (Answer<PagedFile>) invocation ->
        {
            PagedFile file = spy( realPageCache.map( invocation.getArgument( 0, File.class ),
                                                     invocation.getArgument( 1, VersionContextSupplier.class ),
                                                     invocation.getArgument( 2, Integer.class ),
                                                     invocation.getArgument( 3, ImmutableSet.class ) ) );
            files.add( file );
            return file;
        } )
        .when( pageCache ).map( any( File.class ), any( VersionContextSupplier.class ), anyInt(), any() );

        Database database = databaseRule.getDatabase( databaseLayout, fs.get(), pageCache );
        files.clear();

        database.start();
        verify( pageCache, never() ).flushAndForce();

        database.stop();

        verify( pageCache, never() ).flushAndForce( IOLimiter.UNLIMITED );
        for ( PagedFile file : files )
        {
            verify( file, atLeastOnce() ).flushAndForce( IOLimiter.UNLIMITED );
        }
    }

    @Test
    public void flushOfThePageCacheOnShutdownDoesNotHappenIfTheDbIsUnhealthy() throws Throwable
    {
        Health health = mock( DatabaseHealth.class );
        when( health.isHealthy() ).thenReturn( false );
        PageCache pageCache = spy( pageCacheRule.getPageCache( fs.get() ) );

        Dependencies dependencies = new Dependencies();
        dependencies.satisfyDependency( health );
        Database database = databaseRule.getDatabase( databaseLayout, fs.get(), pageCache, dependencies );

        database.start();
        verify( pageCache, never() ).flushAndForce();

        database.stop();
        verify( pageCache, never() ).flushAndForce( IOLimiter.UNLIMITED );
    }

    @Test
    public void logModuleSetUpError()
    {
        Config config = Config.defaults();
        IdGeneratorFactory idGeneratorFactory = mock( IdGeneratorFactory.class );
        Throwable openStoresError = new RuntimeException( "Can't set up modules" );
        doThrow( openStoresError ).when( idGeneratorFactory )
                .create( any(), any( File.class ), any(), anyLong(), anyBoolean(), anyLong(), anyBoolean(), any(), any() );

        AssertableLogProvider logProvider = new AssertableLogProvider();
        SimpleLogService logService = new SimpleLogService( logProvider, logProvider );
        PageCache pageCache = pageCacheRule.getPageCache( fs.get() );
        Dependencies dependencies = new Dependencies();
        dependencies.satisfyDependencies( idGeneratorFactory, config, logService );

        Database database = databaseRule.getDatabase( databaseLayout, fs.get(),
                pageCache, dependencies );

        try
        {
            database.start();
            fail( "Exception expected" );
        }
        catch ( Exception e )
        {
            assertSame( openStoresError, getRootCause( e ) );
        }

        assertThat( logProvider ).forClass( Database.class ).forLevel( WARN )
              .containsMessageWithException( "Exception occurred while starting the database. Trying to stop already started components.",
                      openStoresError );
    }

    @Test
    public void shouldAlwaysShutdownLifeEvenWhenCheckPointingFails() throws Exception
    {
        // Given
        FileSystemAbstraction fs = this.fs.get();
        PageCache pageCache = pageCacheRule.getPageCache( fs );
        Health databaseHealth = mock( DatabaseHealth.class );
        when( databaseHealth.isHealthy() ).thenReturn( true );
        IOException ex = new IOException( "boom!" );
        doThrow( ex ).when( databaseHealth )
                .assertHealthy( IOException.class ); // <- this is a trick to simulate a failure during checkpointing
        Dependencies dependencies = new Dependencies();
        dependencies.satisfyDependencies( databaseHealth );
        Database database = databaseRule.getDatabase( databaseLayout, fs, pageCache, dependencies );
        database.start();

        try
        {
            // When
            database.stop();
            fail( "it should have thrown" );
        }
        catch ( LifecycleException e )
        {
            // Then
            assertEquals( ex, e.getCause() );
        }
    }

    @Test
    public void shouldHaveDatabaseLogServiceInDependencyResolver()
    {
        Dependencies dependencies = new Dependencies();
        Database database = databaseRule.getDatabase( databaseLayout, fs.get(), pageCacheRule.getPageCache( fs.get() ), dependencies );

        database.start();

        try
        {
            var logService = database.getDependencyResolver().resolveDependency( LogService.class );
            assertEquals( database.getLogService(), logService );
            assertThat( logService ).isInstanceOf( DatabaseLogService.class );
        }
        finally
        {
            database.stop();
        }
    }

    private static class FilesCollectionPageCache extends DelegatingPageCache
    {
        private final List<PagedFile> pagedFiles = new ArrayList<>();

        FilesCollectionPageCache( PageCacheRule pageCacheRule, DefaultFileSystemRule fs )
        {
            super( pageCacheRule.getPageCache( fs ) );
        }

        @Override
        public PagedFile map( File file, VersionContextSupplier versionContextSupplier, int pageSize, ImmutableSet<OpenOption> openOptions ) throws IOException
        {
            PagedFile pagedFile = super.map( file, versionContextSupplier, pageSize, openOptions );
            pagedFiles.add( pagedFile );
            return pagedFile;
        }

        List<PagedFile> getPagedFiles()
        {
            return pagedFiles;
        }
    }
}
