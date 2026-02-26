package dto

data class PostWithDetails(
    val post: Post,
    val author: Author?,
    val comments: List<CommentWithAuthor>
)
