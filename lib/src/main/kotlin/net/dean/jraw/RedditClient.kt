package net.dean.jraw

import net.dean.jraw.JrawUtils.jackson
import net.dean.jraw.http.*
import net.dean.jraw.http.oauth.AuthenticationManager
import net.dean.jraw.http.oauth.AuthenticationMethod
import net.dean.jraw.http.oauth.Credentials
import net.dean.jraw.http.oauth.OAuthData
import net.dean.jraw.models.Submission
import net.dean.jraw.pagination.DefaultPaginator
import net.dean.jraw.pagination.Paginator
import net.dean.jraw.ratelimit.LeakyBucketRateLimiter
import net.dean.jraw.ratelimit.RateLimiter
import net.dean.jraw.references.SubmissionReference
import net.dean.jraw.references.SubredditReference
import net.dean.jraw.references.UserReference
import java.util.concurrent.TimeUnit

/**
 * Specialized class for sending requests to [oauth.reddit.com](https://www.reddit.com/dev/api/oauth).
 *
 * This class can also be used to send HTTP requests to other domains, but it is recommended to just use an
 * [HttpAdapter] to do that.
 *
 * RedditClients **must** be authenticated before they can be of any use. The
 * [OAuthHelper][net.dean.jraw.http.oauth.OAuthHelper] class can help you get authenticated.
 *
 * By default, HTTP requests and responses created through this class are logged with [logger]. You can provide your own
 * [HttpLogger] implementation or turn it off entirely using [logHttp]
 *
 * Any requests sent by RedditClients are rate-limited on a per-instance basis. That means that it is possible to
 * consume your app's quota of 60 requests per second using more than one RedditClient authenticated under the same
 * OAuth2 app credentials operating independently of each other. For that reason, it is recommended to have only one
 * instance of the RedditClient per application.
 *
 * By default, any request that responds with a server error code (5XX) will be retried up to five times. You can change
 * this by changing [retryLimit].
 */
class RedditClient(
    val http: HttpAdapter,
    initialOAuthData: OAuthData,
    creds: Credentials
) {
    var logger: HttpLogger = SimpleHttpLogger()
    var logHttp = true

    var retryLimit: Int = 5 // arbitrary number

    var rateLimiter: RateLimiter = LeakyBucketRateLimiter(BURST_LIMIT, RATE_LIMIT, TimeUnit.SECONDS)

    var autoRenew = true
    var authManager = AuthenticationManager(http, creds)

    /** The logged-in user, or null if this RedditClient is authenticated using application-only credentials */
    val username: String? = creds.username

    val authMethod: AuthenticationMethod = creds.authenticationMethod

    init {
        authManager._current = initialOAuthData
    }

    private val authenticatedUsername: String by lazy {
        try {
            me().about().name
        } catch (e: NetworkException) {
            throw IllegalStateException("Expected an authenticated user", e)
        }
    }

    /**
     * Creates a [HttpRequest.Builder], setting `secure(true)`, `host("oauth.reddit.com")`, and the Authorization header
     */
    fun requestStub(): HttpRequest.Builder {
        return HttpRequest.Builder()
            .secure(true)
            .host("oauth.reddit.com")
            .header("Authorization", "bearer ${authManager.accessToken}")
    }


    @Throws(NetworkException::class)
    private fun request(r: HttpRequest, retryCount: Int = 0): HttpResponse {
        if (autoRenew && authManager.needsRenewing() && authManager.canRenew())
            authManager.renew()

        // Try to prevent API errors
        rateLimiter.acquire()

        val res = if (logHttp) {
            // Log the request and response, returning 'res'
            val tag = logger.request(r)
            val res = http.execute(r)
            logger.response(tag, res)
            res
        } else {
            http.execute(r)
        }

        if (res.code in 500..599 && retryCount < retryLimit) {
            return request(r, retryCount + 1)
        }

        if (!res.successful) {
            val stub = jackson.treeToValue(res.json, RedditExceptionStub::class.java) ?: throw NetworkException(res)
            throw stub.create(NetworkException(res))
        }

        return res
    }

    /**
     * Uses the [HttpAdapter] to execute a synchronous HTTP request and returns its JSON value.
     *
     * Throws a [NetworkException] if the response is out of the range of 200..299.
     *
     * Throws a [RedditException] if an API error if the response comes back unsuccessful and a typical error structure
     * is detected in the response.
     *
     * ```
     * val response = reddit.request(reddit.requestStub()
     *     .path("/api/v1/me")
     *     .build())
     * ```
     */
    @Throws(NetworkException::class, RedditException::class)
    fun request(r: HttpRequest): HttpResponse = request(r, retryCount = 0)

    /**
     * Adds a little syntactic sugar to the vanilla `request` method.
     *
     * The [configure] function will be passed the return value of [requestStub], and that [HttpRequest.Builder] will be
     * built and send to `request()`. While this may seem a little complex, it's probably easier to understand through
     * an example:
     *
     * ```
     * val json = reddit.request {
     *     it.path("/api/v1/foo")
     *         .header("X-Foo", "Bar")
     *         .post(mapOf(
     *             "baz" to "qux"
     *         ))
     * }
     * ```
     *
     * This will execute `POST https://oauth.reddit.com/api/v1/foo` with the header 'X-Foo: Bar' and a form body of
     * `baz=qux`.
     *
     * For reference, this same request can be executed like this:
     *
     * ```
     * val json = reddit.request(reddit.requestStub()
     *     .path("/api/v1/me")
     *     .header("X-Foo", "Bar")
     *     .post(mapOf(
     *         "baz" to "qux"
     *     )).build())
     * ```
     *
     * @see requestStub
     */
    @Throws(NetworkException::class)
    fun request(configure: (stub: HttpRequest.Builder) -> HttpRequest.Builder) = request(configure(requestStub()).build())

    /** Gets a UserReference for the currently logged in user */
    fun me() = UserReference(this, UserReference.NAME_SELF)

    /** Gets a UserReference for any user */
    fun user(name: String) = UserReference(this, name)

    /** Gets a [Paginator.Builder] to iterate posts on the front page */
    fun frontPage() = DefaultPaginator.Builder<Submission>(this, baseUrl = "", sortingAsPathParameter = true)

    /**
     * Creates a [SubredditReference]
     *
     * Reddit has some special subreddits:
     *
     * - /r/all - posts from every subreddit
     * - /r/popular - includes posts from subreddits that have opted out of /r/all. Guaranteed to not have NSFW content.
     * - /r/mod - submissions from subreddits the logged-in user moderates
     *
     * Trying to use [SubredditReference.about], [SubredditReference.submit], or the like for these subreddits will
     * likely result in an API-side error.
     */
    fun subreddit(name: String) = SubredditReference(this, name)

    /**
     * Creates a [Paginator.Builder] for more than one subreddit.
     *
     * For example, to fetch 50 posts from /r/pics and /r/funny:
     *
     * ```kotlin
     * val paginator = redditClient.subreddits("pics", "funny").limit(50).build()
     * val posts = paginator.next()
     * ```
     *
     * This works by using a useful little-known trick. You can view multiple subreddits at one time by joining them
     * together with a `+`, like "/r/redditdev+programming+kotlin"
     */
    fun subreddits(first: String, second: String, vararg others: String) =
        SubredditReference(this, arrayOf(first, second, *others).joinToString("+")).posts()

    /**
     * Gets a random subreddit. Although this method is decorated with [EndpointImplementation], it does not execute a
     * HTTP request and is not a blocking call. This method is equivalent to
     *
     * ```kotlin
     * reddit.subreddit("random")
     * ```
     *
     * @see SubredditReference.randomSubmission
     */
    @EndpointImplementation(Endpoint.GET_RANDOM)
    fun randomSubreddit() = subreddit("random")

    /**
     * Creates a SubmissionReference. Note that `id` is NOT a full name (like `t3_6afe8u`), but rather an ID
     * (like `6afe8u`)
     */
    fun submission(id: String) = SubmissionReference(this, id)

    /**
     * Returns the name of the logged-in user, or throws an IllegalStateException if there is none. If this client was
     * authenticated with a non-script OAuth2 app, the first time this method is called, it will send a network request.
     */
    fun requireAuthenticatedUser(): String {
        if (authMethod.isUserless)
            throw IllegalStateException("Expected the RedditClient to have an active user, was authenticated with " +
                authMethod)
        // Use `username`. If that's null (authenticated using a non-script app), fall back to `authenticatedUsername`
        return username ?: authenticatedUsername
    }

    companion object {
        /** Amount of requests per second reddit allows for OAuth2 apps */
        const val RATE_LIMIT = 1L
        const val BURST_LIMIT = 5L
    }
}
