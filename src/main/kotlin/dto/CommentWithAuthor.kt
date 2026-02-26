package dto

data class CommentWithAuthor(
    val author: Author?,
    val comment: Comment,
)
