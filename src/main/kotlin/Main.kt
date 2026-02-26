

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dto.Author
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import dto.Comment
import dto.CommentWithAuthor
import dto.Post
import dto.PostWithDetails
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private val gson = Gson()
private const val BASE_URL = "http://127.0.0.1:9999"
private val client = OkHttpClient.Builder()
    .addInterceptor(HttpLoggingInterceptor(::println).apply {
        level = HttpLoggingInterceptor.Level.BODY
    })
    .connectTimeout(30, TimeUnit.SECONDS)
    .build()

fun main() {
    println("╔════════════════════════════════════════╗")
    println("║   📱 Загрузчик постов с авторами       ║")
    println("╚════════════════════════════════════════╝")

    with(CoroutineScope(EmptyCoroutineContext)) {
        launch {
            try {
                println("⏳ Загрузка данных...")
                // Получаем посты
                val posts = getPosts(client)
                println("✅ Загружено постов: ${posts.size}")

                // Загружаем авторов для постов и комментарии с авторами
                val postsWithDetails = loadPostsWithAuthorsAndComments(posts)

                // Выводим результат
                printPostsWithDetails(postsWithDetails)

            } catch (e: Exception) {
                println("❌ Ошибка: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    // Даем время на выполнение запросов
    Thread.sleep(30_000L)
}

suspend fun loadPostsWithAuthorsAndComments(posts: List<Post>): List<PostWithDetails> = coroutineScope {
    // Кэш для авторов, чтобы не загружать одного автора несколько раз
    val authorCache = mutableMapOf<Long, Author>()

    // Сначала загружаем всех уникальных авторов постов
    val postAuthorIds = posts.map { it.authorId }.toSet()
    val postAuthors = postAuthorIds.map { authorId ->
        async {
            authorId to loadAuthor(authorId)
        }
    }.awaitAll()

    // Фильтруем только успешно загруженных авторов (не null)
    postAuthors.forEach { (id, author) ->
        if (author != null) {
            authorCache[id] = author
        }
    }

    // Теперь для каждого поста загружаем комментарии и их авторов
    posts.map { post ->
        async {
            // Получаем комментарии для поста
            val comments = getComments(client, post.id)

            // Загружаем авторов комментариев (только тех, кого еще нет в кэше)
            val commentAuthorIds = comments.map { it.authorId }.toSet()
            val newAuthorIds = commentAuthorIds - authorCache.keys

            if (newAuthorIds.isNotEmpty()) {
                val newAuthors = newAuthorIds.map { authorId ->
                    async {
                        authorId to loadAuthor(authorId)
                    }
                }.awaitAll()

                // Добавляем только успешно загруженных авторов
                newAuthors.forEach { (id, author) ->
                    if (author != null) {
                        authorCache[id] = author
                    }
                }
            }

            // Создаем комментарии с авторами
            val commentsWithAuthors = comments.map { comment ->
                CommentWithAuthor(
                    comment = comment,
                    author = authorCache[comment.authorId]
                )
            }

            // Создаем пост с деталями
            PostWithDetails(
                post = post,
                author = authorCache[post.authorId],
                comments = commentsWithAuthors
            )
        }
    }.awaitAll()
}

suspend fun loadAuthor(authorId: Long): Author? {
    return try {
        makeRequest("$BASE_URL/api/authors/$authorId", client, object : TypeToken<Author>() {})
    } catch (e: Exception) {
        println("⚠️ Не удалось загрузить автора с ID $authorId: ${e.message}")
        null
    }
}

fun printPostsWithDetails(postsWithDetails: List<PostWithDetails>) {
    println("\n" + "═".repeat(100))
    println("📱 РЕЗУЛЬТАТЫ ЗАГРУЗКИ")
    println("═".repeat(100))

    if (postsWithDetails.isEmpty()) {
        println("❌ Нет постов для отображения")
        return
    }

    postsWithDetails.forEachIndexed { index, item ->
        val post = item.post
        val author = item.author
        val comments = item.comments

        println("\n📌 ПОСТ #${index + 1} (ID: ${post.id})")

        // Информация об авторе поста
        if (author != null) {
            println("   👤 Автор: ${author.name} (ID: ${post.authorId})")
            println("   🖼️ Аватар: ${author.avatar}")
        } else {
            println("   👤 Автор: ID ${post.authorId} (не загружен)")
            println("   🖼️ Аватар: не доступен")
        }

        // Содержание поста
        println("   💬 Содержание: ${post.content}")
        println("   📅 Дата: ${post.published}")
        println("   ❤️ Лайки: ${post.likes} ${if (post.likedByMe) "👍" else ""}")

        // Вложение если есть
        post.attachment?.let { attachment ->
            println("   📎 Вложение: ${attachment.url}")
            println("   📝 Описание: ${attachment.description}")
            println("   🏷️ Тип: ${attachment.type}")
        }

        // Комментарии
        if (comments.isNotEmpty()) {
            println("\n   💭 КОММЕНТАРИИ (${comments.size}):")
            comments.forEachIndexed { commentIndex, commentWithAuthor ->
                val comment = commentWithAuthor.comment
                val commentAuthor = commentWithAuthor.author

                println("      ${commentIndex + 1}. Комментарий ID: ${comment.id}")

                if (commentAuthor != null) {
                    println("         👤 Автор: ${commentAuthor.name} (ID: ${comment.authorId})")
                    println("         🖼️ Аватар: ${commentAuthor.avatar}")
                } else {
                    println("         👤 Автор: ID ${comment.authorId} (не загружен)")
                }

                println("         💬 ${comment.content}")
                println("         ❤️ ${comment.likes} ${if (comment.likedByMe) "👍" else ""}")
                println("         " + "─".repeat(40))
            }
        } else {
            println("\n   💭 Комментариев нет")
        }

        println("   " + "─".repeat(90))
    }

    println("\n📊 ВСЕГО ПОСТОВ: ${postsWithDetails.size}")

    // Подсчет статистики
    val totalComments = postsWithDetails.sumOf { it.comments.size }
    val loadedAuthors = postsWithDetails.count { it.author != null }
    val loadedCommentAuthors = postsWithDetails.sumOf { post ->
        post.comments.count { it.author != null }
    }

    println("📊 ВСЕГО КОММЕНТАРИЕВ: $totalComments")
    println("📊 Загружено авторов постов: $loadedAuthors из ${postsWithDetails.size}")
    println("📊 Загружено авторов комментариев: $loadedCommentAuthors из $totalComments")
    println("═".repeat(100))
}

suspend fun OkHttpClient.apiCall(url: String): Response {
    return suspendCoroutine { continuation ->
        Request.Builder()
            .url(url)
            .build()
            .let(::newCall)
            .enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response)
                }

                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }
            })
    }
}

suspend fun <T> makeRequest(url: String, client: OkHttpClient, typeToken: TypeToken<T>): T =
    withContext(Dispatchers.IO) {
        client.apiCall(url)
            .let { response ->
                if (!response.isSuccessful) {
                    response.close()
                    throw RuntimeException("HTTP ${response.code}: ${response.message}")
                }
                val body = response.body ?: throw RuntimeException("response body is null")
                gson.fromJson(body.string(), typeToken.type)
            }
    }

suspend fun getPosts(client: OkHttpClient): List<Post> =
    makeRequest("$BASE_URL/api/slow/posts", client, object : TypeToken<List<Post>>() {})

suspend fun getComments(client: OkHttpClient, id: Long): List<Comment> =
    makeRequest("$BASE_URL/api/slow/posts/$id/comments", client, object : TypeToken<List<Comment>>() {})
