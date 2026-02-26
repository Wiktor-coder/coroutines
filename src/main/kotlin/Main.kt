//package ru.netology.coroutines

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import dto.Comment
import dto.Post
import dto.PostWithComments
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
    with(CoroutineScope(EmptyCoroutineContext)) {
        launch {
            try {
                // Получаем посты
                val posts = getPosts(client)

                // Параллельно получаем комментарии для всех постов
                val postsWithComments = posts
                    .map { post ->
                        async {
                            PostWithComments(post, getComments(client, post.id))
                        }
                    }
                    .awaitAll()

                // ВЫВОДИМ СПИСОК В КОНСОЛЬ
                printPostsWithComments(postsWithComments)

            } catch (e: Exception) {
                println("❌ Ошибка: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    // Даем время на выполнение запросов
    println("⏳ Загрузка данных...")
    Thread.sleep(30_000L)
}

// Функция для красивого вывода постов с комментариями
fun printPostsWithComments(postsWithComments: List<PostWithComments>) {
    println("\n" + "=".repeat(80))
    println("📱 ПОЛУЧЕННЫЕ ДАННЫЕ")
    println("=".repeat(80))

    if (postsWithComments.isEmpty()) {
        println("❌ Нет постов для отображения")
        return
    }

    postsWithComments.forEachIndexed { index, item ->
        val post = item.post
        val comments = item.comments

        println("\n📌 ПОСТ #${index + 1} (ID: ${post.id})")
        println("   👤 Автор: ${post.author}")
        println("   🖼️ Аватар: ${post.authorAvatar}")
        println("   💬 Содержание: ${post.content}")
        println("   📅 Дата: ${post.published}")
        println("   ❤️ Лайки: ${post.likes} ${if (post.likedByMe) "(Вам нравится)" else ""}")

        if (comments.isNotEmpty()) {
            println("\n   💭 КОММЕНТАРИИ (${comments.size}):")
            comments.forEachIndexed { commentIndex, comment ->
                println("      ${commentIndex + 1}. ${comment.author}:")
                println("         ${comment.content}")
                println("         🖼️ Аватар: ${comment.authorAvatar}")
                println("         ❤️ ${comment.likes} ${if (comment.likedByMe) "(Вам нравится)" else ""}")
                println("         " + "-".repeat(40))
            }
        } else {
            println("\n   💭 Комментариев нет")
        }

        println("   " + "═".repeat(70))
    }

    println("\n📊 ВСЕГО ПОСТОВ: ${postsWithComments.size}")
    println("=".repeat(80))
}

// Вспомогательная функция для вывода только постов (без комментариев)
fun printPosts(posts: List<Post>) {
    println("\n" + "=".repeat(80))
    println("📱 ПОСТЫ (БЕЗ КОММЕНТАРИЕВ)")
    println("=".repeat(80))

    posts.forEachIndexed { index, post ->
        println("\n📌 ПОСТ #${index + 1} (ID: ${post.id})")
        println("   👤 Автор: ${post.author}")
        println("   🖼️ Аватар: ${post.authorAvatar}")
        println("   💬 Содержание: ${post.content}")
        println("   ❤️ Лайки: ${post.likes} ${if (post.likedByMe) "(Вам нравится)" else ""}")
        println("   " + "─".repeat(50))
    }

    println("\n📊 Всего постов: ${posts.size}")
    println("=".repeat(80))
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