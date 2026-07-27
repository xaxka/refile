package xa.refile.data

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import xa.refile.BuildConfig
import xa.refile.core.naming.PresetRepository
import xa.refile.data.crypto.KeystoreCrypto
import xa.refile.data.db.AppDatabase
import xa.refile.data.db.RenameBatchDao
import xa.refile.data.db.ServerConfigDao
import xa.refile.data.db.TmdbCacheDao
import xa.refile.data.repository.ServerRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt DI 模块集合（计划 §M1 SubTask 1.3.4）。
 *
 * 拆为多个 [Module]：数据库、加解密、服务器仓库、命名预设、WorkManager。
 * 全部安装到 [SingletonComponent]，应用进程级单例。
 */

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "refile.db")
            .apply {
                // 仅 debug 期允许版本变更时直接重建；release 需提供正规 Migration，
                // 缺失时抛异常而非静默清空用户数据（Task 5.1 修复）。
                if (BuildConfig.DEBUG) {
                    fallbackToDestructiveMigration()
                }
            }
            .build()

    @Provides
    fun provideServerConfigDao(db: AppDatabase): ServerConfigDao = db.serverConfigDao()

    /** Task 5.1.1：重命名批次/条目 DAO。 */
    @Provides
    fun provideRenameBatchDao(db: AppDatabase): RenameBatchDao = db.renameBatchDao()

    /** Task 2.3.4：TMDB 响应缓存 DAO。 */
    @Provides
    fun provideTmdbCacheDao(db: AppDatabase): TmdbCacheDao = db.tmdbCacheDao()
}

@Module
@InstallIn(SingletonComponent::class)
object CryptoModule {

    @Provides
    @Singleton
    fun provideKeystoreCrypto(@ApplicationContext context: Context): KeystoreCrypto =
        KeystoreCrypto(context)
}

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideServerRepository(
        dao: ServerConfigDao,
        crypto: KeystoreCrypto,
    ): ServerRepository = ServerRepository(dao, crypto)
}

@Module
@InstallIn(SingletonComponent::class)
object NamingPresetModule {

    /** 命名预设仓库（Task 3.3 模板编辑器注入）。无状态，单例即可。 */
    @Provides
    @Singleton
    fun providePresetRepository(): PresetRepository = PresetRepository()
}

@Module
@InstallIn(SingletonComponent::class)
object WorkManagerModule {

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)
}
