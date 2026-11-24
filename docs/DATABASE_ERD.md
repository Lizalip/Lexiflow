# LexiFlow · Связи проектируемой базы

**Черновик для согласования.** Диаграммы образуют одну модель: повторяющееся имя обозначает ту же таблицу. У линии подписан столбец внешнего ключа в дочерней таблице. Вертикальная черта означает один объект, круг — необязательность, «лапка» — множество. Полные столбцы, составные ключи, частичные ограничения и индексы приведены в [физической модели](DATABASE_DESIGN_DRAFT.md).

## Источники и права

```mermaid
erDiagram
    source ||--o{ release : source_id
    release ||--o{ evidence : release_id
    license |o--o{ evidence : license_id
```

## Статьи, значения и примеры

```mermaid
erDiagram
    language ||--o{ entry : lang
    pos |o--o{ entry : pos_id
    evidence ||--o| entry : evidence_id
    entry ||--o{ sense : entry_id
    evidence ||--o| sense : evidence_id
    sense ||--o{ gloss : sense_id
    language ||--o{ gloss : lang
    evidence ||--o{ gloss : evidence_id
    sense ||--o{ translation : sense_id
    language ||--o{ translation : lang
    evidence ||--o{ translation : evidence_id
    sense ||--o{ example : sense_id
    language ||--o{ example : lang
    evidence ||--o{ example : evidence_id
    example ||--o{ example_translation : example_id
    language ||--o{ example_translation : lang
    evidence ||--o{ example_translation : evidence_id
```

## Формы, произношение и аннотации

```mermaid
erDiagram
    entry ||--o{ form : entry_id
    evidence ||--o{ form : evidence_id
    form ||--o{ form_feature : form_id
    entry ||--o{ pronunciation : entry_id
    evidence ||--o{ pronunciation : evidence_id
    evidence ||--o{ media : evidence_id
    pronunciation ||--o{ pron_audio : pron_id
    media ||--o{ pron_audio : media_id
    sense ||--o{ illustration : sense_id
    media ||--o{ illustration : media_id
    evidence ||--o{ illustration : evidence_id
    sense ||--o{ sense_tag : sense_id
    tag ||--o{ sense_tag : tag_id
    evidence ||--o{ sense_tag : evidence_id
    entry |o--o{ level : entry_id
    sense |o--o{ level : sense_id
    evidence ||--o{ level : evidence_id
```

## Личное хранилище

```mermaid
erDiagram
    saved ||--o{ own_translation : saved_id
    collection ||--o{ membership : collection_id
    saved ||--o{ membership : saved_id
    saved ||--o{ card : saved_id
    card ||--o{ review_event : card_id
```

Для `level` действует XOR: каждая оценка адресована ровно одной статье или одному значению. Для `pron_audio` допустим файл типа audio, для `illustration` — image. Дневная статистика вычисляется группировкой `review_event`; отдельной таблицы итогов нет.

## Граница файлов

Пунктирные связи ниже разрешаются приложением по постоянным ID. Кардинальности относятся к моменту сохранения. После замены корпуса ссылка может перестать разрешаться; исходный ID и подпись остаются в личном файле.

```mermaid
erDiagram
    entry ||..o{ saved : entry_ref
    sense |o..o{ saved : sense_ref
```
