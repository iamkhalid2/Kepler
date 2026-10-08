package app.kepler.core.database

import app.kepler.core.database.db.*
import app.kepler.core.model.Annotation
import app.kepler.core.model.AnnotationVia
import app.kepler.core.model.AudioSegment
import app.kepler.core.model.Book
import app.kepler.core.model.Bookmark
import app.kepler.core.model.Card
import app.kepler.core.model.CardType
import app.kepler.core.model.ContentType
import app.kepler.core.model.Position
import app.kepler.core.model.PositionKind
import app.kepler.core.model.Section
import app.kepler.core.model.SectionKind
import app.kepler.core.model.Sentence
import app.kepler.core.model.SyncRow
import app.kepler.core.model.VoiceRef

internal fun BookEntity.toModel() = Book(
    id, title, authors, language, enumOr(contentType, ContentType.MIXED), epubPath, coverPath,
    addedAt, lastOpenedAt, sentenceCount, readFraction, listenFraction,
)

internal fun SectionEntity.toModel() = Section(
    id, bookId, parentId, sectionIndex, level, title, href, enumOr(kind, SectionKind.BODY),
    startOrdinal, endOrdinal, included,
)

internal fun SentenceEntity.toModel() = Sentence(bookId, ordinal, sectionId, href, text, ctxBefore, ctxAfter, paragraphIndex)

internal fun PositionEntity.toModel() = Position(bookId, enumOr(kind, PositionKind.READING), ordinal, updatedAt)

internal fun AnnotationEntity.toModel() = Annotation(id, bookId, startOrdinal, endOrdinal, quote, color, note, enumOr(via, AnnotationVia.READ), createdAt)

internal fun Annotation.toEntity() = AnnotationEntity(id, bookId, startOrdinal, endOrdinal, quote, color, note, via.name, createdAt)

internal fun BookmarkEntity.toModel() = Bookmark(id, bookId, ordinal, label, createdAt)

internal fun AudioSegmentEntity.toModel() = AudioSegment(
    id, bookId, startOrdinal, endOrdinal, filePath, durationMs, VoiceRef(voiceProvider, voiceId, voiceName), take,
)

internal fun SyncRowEntity.toModel() = SyncRow(bookId, ordinal, segmentId, startMs, endMs)

internal fun CardEntity.toModel() = Card(
    id, bookId, enumOr(type, CardType.QA), sourceStart, sourceEnd, front, back, userAnswer, dueAt,
    stability, difficulty, reps, lapses, state, lastReviewAt, createdAt,
)

internal fun Card.toEntity() = CardEntity(
    id, bookId, type.name, sourceStart, sourceEnd, front, back, userAnswer, dueAt,
    stability, difficulty, reps, lapses, state, lastReviewAt, createdAt,
)

private inline fun <reified E : Enum<E>> enumOr(name: String, fallback: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: fallback
