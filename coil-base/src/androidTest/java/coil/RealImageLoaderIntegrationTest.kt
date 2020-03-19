@file:OptIn(ExperimentalCoilApi::class)

package coil

import android.content.ContentResolver.SCHEME_ANDROID_RESOURCE
import android.content.ContentResolver.SCHEME_CONTENT
import android.content.ContentResolver.SCHEME_FILE
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.widget.ImageView
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import coil.annotation.ExperimentalCoilApi
import coil.api.get
import coil.api.getAny
import coil.api.load
import coil.api.loadAny
import coil.base.test.R
import coil.bitmappool.BitmapPool
import coil.decode.BitmapFactoryDecoder
import coil.decode.DataSource
import coil.decode.DecodeResult
import coil.decode.Decoder
import coil.decode.Options
import coil.fetch.AssetUriFetcher.Companion.ASSET_FILE_PATH_ROOT
import coil.fetch.DrawableResult
import coil.fetch.Fetcher
import coil.request.CachePolicy
import coil.request.NullRequestDataException
import coil.request.Request
import coil.size.PixelSize
import coil.size.Size
import coil.transform.CircleCropTransformation
import coil.util.Utils
import coil.util.createMockWebServer
import coil.util.createOptions
import coil.util.getDrawableCompat
import coil.util.size
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Cache
import okhttp3.mockwebserver.MockWebServer
import okio.BufferedSource
import okio.buffer
import okio.sink
import okio.source
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Integration tests for [RealImageLoader].
 */
class RealImageLoaderIntegrationTest {

    companion object {
        private const val IMAGE_NAME = "normal.jpg"
        private const val IMAGE_SIZE = 443291L
    }

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var imageLoader: RealImageLoader

    @Before
    fun before() {
        context = ApplicationProvider.getApplicationContext()
        server = createMockWebServer(context, IMAGE_NAME, IMAGE_NAME)
        imageLoader = ImageLoader(context) as RealImageLoader
    }

    @After
    fun after() {
        server.shutdown()
        imageLoader.shutdown()
    }

    // region Test all the supported data types.

    @Test
    fun string() {
        val data = server.url(IMAGE_NAME).toString()
        testLoad(data)
        testGet(data)
    }

    @Test
    fun httpUri() {
        val data = server.url(IMAGE_NAME).toString().toUri()
        testLoad(data)
        testGet(data)
    }

    @Test
    fun httpUrl() {
        val data = server.url(IMAGE_NAME)
        testLoad(data)
        testGet(data)
    }

    @Test
    fun resourceInt() {
        val data = R.drawable.normal
        testLoad(data)
        testGet(data)
    }

    @Test
    fun resourceIntVector() {
        val data = R.drawable.ic_android
        testLoad(data, PixelSize(100, 100))
        testGet(data, PixelSize(100, 100))
    }

    @Test
    fun resourceUriInt() {
        val data = "$SCHEME_ANDROID_RESOURCE://${context.packageName}/${R.drawable.normal}".toUri()
        testLoad(data)
        testGet(data)
    }

    @Test
    fun resourceUriIntVector() {
        val data = "$SCHEME_ANDROID_RESOURCE://${context.packageName}/${R.drawable.ic_android}".toUri()
        testLoad(data, PixelSize(100, 100))
        testGet(data, PixelSize(100, 100))
    }

    @Test
    fun resourceUriString() {
        val data = "$SCHEME_ANDROID_RESOURCE://${context.packageName}/drawable/normal".toUri()
        testLoad(data)
        testGet(data)
    }

    @Test
    fun resourceUriStringVector() {
        val data = "$SCHEME_ANDROID_RESOURCE://${context.packageName}/drawable/ic_android".toUri()
        testLoad(data, PixelSize(100, 100))
        testGet(data, PixelSize(100, 100))
    }

    @Test
    fun file() {
        val data = copyNormalImageAssetToCacheDir()
        testLoad(data)
        testGet(data)
    }

    @Test
    fun fileUri() {
        val data = copyNormalImageAssetToCacheDir().toUri()
        testLoad(data)
        testGet(data)
    }

    @Test
    fun assetUri() {
        val data = "$SCHEME_FILE:///$ASSET_FILE_PATH_ROOT/exif/large_metadata.jpg".toUri()
        testLoad(data, PixelSize(75, 100))
        testGet(data, PixelSize(100, 133))
    }

    @Test
    fun contentUri() {
        val data = "$SCHEME_CONTENT://coil/$IMAGE_NAME".toUri()
        testLoad(data)
        testGet(data)
    }

    @Test
    fun drawable() {
        val data = context.getDrawableCompat(R.drawable.normal)
        val expectedSize = PixelSize(1080, 1350)
        testLoad(data, expectedSize)
        testGet(data, expectedSize)
    }

    @Test
    fun bitmap() {
        val data = (context.getDrawableCompat(R.drawable.normal) as BitmapDrawable).bitmap
        val expectedSize = PixelSize(1080, 1350)
        testLoad(data, expectedSize)
        testGet(data, expectedSize)
    }

    // endregion

    @Test
    fun unsupportedDataThrows() {
        val data = Any()
        assertFailsWith<IllegalStateException> { testLoad(data) }
        assertFailsWith<IllegalStateException> { testGet(data) }
    }

    @Test
    fun memoryCacheDisabled_preloadDoesNotDecode() {
        val imageLoader = ImageLoader.Builder(context)
            .componentRegistry {
                add(object : Decoder {
                    override fun handles(source: BufferedSource, mimeType: String?) = true

                    override suspend fun decode(
                        pool: BitmapPool,
                        source: BufferedSource,
                        size: Size,
                        options: Options
                    ) = throw IllegalStateException("Decode should not be called.")
                })
            }
            .build()

        val url = server.url(IMAGE_NAME)
        val cacheFolder = Utils.getDefaultCacheDirectory(context).apply {
            deleteRecursively()
            mkdirs()
        }

        assertTrue(cacheFolder.listFiles().isNullOrEmpty())

        runBlocking {
            suspendCancellableCoroutine<Unit> { continuation ->
                imageLoader.load(context, url) {
                    memoryCachePolicy(CachePolicy.DISABLED)
                    listener(
                        onSuccess = { _, _ -> continuation.resume(Unit) },
                        onError = { _, throwable -> continuation.resumeWithException(throwable) },
                        onCancel = { continuation.resumeWithException(CancellationException()) }
                    )
                }
            }
        }

        val cacheFile = cacheFolder.listFiles().orEmpty().find { it.name.contains(Cache.key(url)) && it.length() == IMAGE_SIZE }
        assertNotNull(cacheFile, "Did not find the image file in the disk cache.")
    }

    @Test
    fun memoryCacheDisabled_getDoesDecode() {
        var numDecodes = 0
        val imageLoader = ImageLoader.Builder(context)
            .componentRegistry {
                add(object : Decoder {
                    private val delegate = BitmapFactoryDecoder(context)

                    override fun handles(source: BufferedSource, mimeType: String?) = true

                    override suspend fun decode(
                        pool: BitmapPool,
                        source: BufferedSource,
                        size: Size,
                        options: Options
                    ): DecodeResult {
                        numDecodes++
                        return delegate.decode(pool, source, size, options)
                    }
                })
            }
            .build()

        val url = server.url(IMAGE_NAME)
        val cacheFolder = Utils.getDefaultCacheDirectory(context).apply {
            deleteRecursively()
            mkdirs()
        }

        assertTrue(cacheFolder.listFiles().isNullOrEmpty())

        runBlocking {
            imageLoader.get(url) {
                memoryCachePolicy(CachePolicy.DISABLED)
            }
        }

        val cacheFile = cacheFolder.listFiles().orEmpty().find { it.name.contains(Cache.key(url)) && it.length() == IMAGE_SIZE }
        assertNotNull(cacheFile, "Did not find the image file in the disk cache.")
        assertEquals(1, numDecodes)
    }

    @Test
    fun applyTransformations_transformationsConvertDrawableToBitmap() {
        val drawable = ColorDrawable(Color.BLACK)
        val size = PixelSize(100, 100)
        val result = runBlocking {
            imageLoader.applyTransformations(
                scope = this,
                result = DrawableResult(
                    drawable = drawable,
                    isSampled = false,
                    dataSource = DataSource.MEMORY
                ),
                transformations = listOf(CircleCropTransformation()),
                size = size,
                options = createOptions()
            )
        }

        val resultDrawable = result.drawable
        assertTrue(resultDrawable is BitmapDrawable)
        assertEquals(resultDrawable.bitmap.size, size)
    }

    @Test
    fun applyTransformations_emptyTransformationsDoesNotConvertDrawable() {
        val drawable = ColorDrawable(Color.BLACK)
        val size = PixelSize(100, 100)
        val result = runBlocking {
            imageLoader.applyTransformations(
                scope = this,
                result = DrawableResult(
                    drawable = drawable,
                    isSampled = false,
                    dataSource = DataSource.MEMORY
                ),
                transformations = emptyList(),
                size = size,
                options = createOptions()
            )
        }

        assertSame(drawable, result.drawable)
    }

    @Test
    fun nullRequestDataShowsFallbackDrawable() {
        val error = ColorDrawable(Color.BLUE)
        val fallback = ColorDrawable(Color.BLACK)

        runBlocking {
            suspendCancellableCoroutine<Unit> { continuation ->
                var hasCalledTargetOnError = false

                imageLoader.loadAny(context, null) {
                    size(100, 100)
                    error(error)
                    fallback(fallback)
                    target(
                        onStart = { throw IllegalStateException() },
                        onError = { drawable ->
                            check(drawable === fallback)
                            hasCalledTargetOnError = true
                        },
                        onSuccess = { throw IllegalStateException() }
                    )
                    listener(
                        onStart = { throw IllegalStateException() },
                        onSuccess = { _, _ -> throw IllegalStateException() },
                        onCancel = { throw IllegalStateException() },
                        onError = { _, throwable ->
                            if (hasCalledTargetOnError && throwable is NullRequestDataException) {
                                continuation.resume(Unit)
                            } else {
                                continuation.resumeWithException(throwable)
                            }
                        }
                    )
                }
            }
        }
    }

    @Test
    fun eventListenerMethodsAreCalled() {
        class MethodChecker(private val eventName: String) {

            private val isCalled = AtomicBoolean(false)

            fun markCalled() {
                require(!isCalled.getAndSet(true)) { "$eventName was called more than once." }
            }

            fun requireCalled() {
                require(isCalled.get()) { "$eventName was NOT called at least once." }
            }

            fun requireNotCalled() {
                require(!isCalled.get()) { "$eventName was called once." }
            }
        }

        val eventListener = object : EventListener {

            val onStart = MethodChecker("onStart")
            val mapStart = MethodChecker("mapStart")
            val mapEnd = MethodChecker("mapEnd")
            val resolveSizeStart = MethodChecker("resolveSizeStart")
            val resolveSizeEnd = MethodChecker("resolveSizeEnd")
            val fetchStart = MethodChecker("fetchStart")
            val fetchEnd = MethodChecker("fetchEnd")
            val decodeStart = MethodChecker("decodeStart")
            val decodeEnd = MethodChecker("decodeEnd")
            val transformStart = MethodChecker("transformStart")
            val transformEnd = MethodChecker("transformEnd")
            val onSuccess = MethodChecker("transformEnd")
            val onCancel = MethodChecker("transformEnd")
            val onError = MethodChecker("transformEnd")

            override fun onStart(request: Request) = onStart.markCalled()
            override fun mapStart(request: Request) = mapStart.markCalled()
            override fun mapEnd(request: Request, mappedData: Any) = mapEnd.markCalled()
            override fun resolveSizeStart(request: Request) = resolveSizeStart.markCalled()
            override fun resolveSizeEnd(request: Request, size: Size) = resolveSizeEnd.markCalled()
            override fun fetchStart(request: Request, fetcher: Fetcher<*>, options: Options) = fetchStart.markCalled()
            override fun fetchEnd(request: Request, fetcher: Fetcher<*>, options: Options) = fetchEnd.markCalled()
            override fun decodeStart(request: Request, decoder: Decoder, options: Options) = decodeStart.markCalled()
            override fun decodeEnd(request: Request, decoder: Decoder, options: Options) = decodeEnd.markCalled()
            override fun transformStart(request: Request) = transformStart.markCalled()
            override fun transformEnd(request: Request) = transformEnd.markCalled()
            override fun onSuccess(request: Request, source: DataSource) = onSuccess.markCalled()
            override fun onCancel(request: Request) = onCancel.markCalled()
            override fun onError(request: Request, throwable: Throwable) = onError.markCalled()
        }

        runBlocking {
            val imageLoader = ImageLoader.Builder(context)
                .eventListener(eventListener)
                .build()

            testLoad(copyNormalImageAssetToCacheDir(), imageLoader = imageLoader)
        }

        eventListener.apply {
            onStart.requireCalled()
            mapStart.requireCalled()
            mapEnd.requireCalled()
            resolveSizeStart.requireCalled()
            resolveSizeEnd.requireCalled()
            fetchStart.requireCalled()
            fetchEnd.requireCalled()
            decodeStart.requireCalled()
            decodeEnd.requireCalled()
            transformStart.requireCalled()
            transformEnd.requireCalled()
            onSuccess.requireCalled()
            onCancel.requireNotCalled()
            onError.requireNotCalled()
        }
    }

    private fun testLoad(
        data: Any,
        expectedSize: PixelSize = PixelSize(80, 100),
        imageLoader: ImageLoader = this.imageLoader
    ) {
        val imageView = ImageView(context)
        imageView.scaleType = ImageView.ScaleType.FIT_CENTER

        assertNull(imageView.drawable)

        runBlocking {
            suspendCancellableCoroutine<Unit> { continuation ->
                imageLoader.loadAny(context, data) {
                    target(imageView)
                    size(100, 100)
                    listener(
                        onSuccess = { _, _ -> continuation.resume(Unit) },
                        onError = { _, throwable -> continuation.resumeWithException(throwable) },
                        onCancel = { continuation.resumeWithException(CancellationException()) }
                    )
                }
            }
        }

        val drawable = imageView.drawable
        assertTrue(drawable is BitmapDrawable)
        assertEquals(expectedSize, drawable.bitmap.size)
    }

    private fun testGet(data: Any, expectedSize: PixelSize = PixelSize(100, 125)) {
        val drawable = runBlocking {
            imageLoader.getAny(data) {
                size(100, 100)
            }
        }

        assertTrue(drawable is BitmapDrawable)
        assertEquals(expectedSize, drawable.bitmap.size)
    }

    private fun copyNormalImageAssetToCacheDir(): File {
        val file = File(context.cacheDir, IMAGE_NAME)
        val source = context.assets.open(IMAGE_NAME).source()
        val sink = file.sink().buffer()
        source.use { sink.use { sink.writeAll(source) } }
        return file
    }
}
