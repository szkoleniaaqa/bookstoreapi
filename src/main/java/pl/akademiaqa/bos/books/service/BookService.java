package pl.akademiaqa.bos.books.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.SocketUtils;
import pl.akademiaqa.bos.autors.db.AuthorJpaRepository;
import pl.akademiaqa.bos.autors.domain.Author;
import pl.akademiaqa.bos.books.api.payload.CreateBookPayload;
import pl.akademiaqa.bos.books.api.payload.UpdateBookCoverPayload;
import pl.akademiaqa.bos.books.api.payload.UpdateBookPayload;
import pl.akademiaqa.bos.books.api.response.CreateBookResponse;
import pl.akademiaqa.bos.books.api.response.PartialUpdateBookResponse;
import pl.akademiaqa.bos.books.api.response.UpdateBookResponse;
import pl.akademiaqa.bos.books.service.port.IBookService;
import pl.akademiaqa.bos.books.db.BookJpaRepository;
import pl.akademiaqa.bos.books.domain.Book;
import pl.akademiaqa.bos.commons.StringBuilderPlus;
import pl.akademiaqa.bos.uploads.service.port.IUploadService;
import pl.akademiaqa.bos.uploads.domain.Upload;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static pl.akademiaqa.bos.commons.IsNullOrEmpty.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookService implements IBookService {
    private final BookJpaRepository repository;
    private final AuthorJpaRepository authorRepository;
    private final IUploadService upload;

    @Override
    public Optional<Book> findById(Long id) {
        return repository.findById(id);
    }

    @Override
    public List<Book> findByTitle(String title) {
        return repository.findByTitle(title);
    }

    @Override
    public List<Book> findByAuthor(String author) {
        return repository.findByAuthor(author);
    }

    @Override
    public Optional<Book> findOneByTitle(String title) {
        return repository.findByTitle(title)
                .stream()
                .findFirst();
    }

    @Override
    public List<Book> findAll() {
        return repository.findAllEager().stream().sorted().collect(Collectors.toList());
    }

    @Override
    public List<Book> admin_findAll() {
        return repository.admin_findAllBooks().stream().sorted().collect(Collectors.toList());
    }

    @Override
    public List<Book> findByTitleAndAuthor(String title, String author) {
        return repository.findByTitleAndAuthor(title, author);
    }

    @Override
    @Transactional
    public CreateBookResponse createBook(CreateBookPayload payload) {
        System.out.println("SAVE TO DB: " + payload);
        Book savedBook = repository.save(toBook(payload));
        if (savedBook == null) {
            return CreateBookResponse.failure("Can not create a book");
        }
        return CreateBookResponse.success(savedBook.getId());
    }

    private Book toBook(CreateBookPayload payload) {
        Book book = new Book(payload.getTitle(), payload.getYear(), payload.getPrice(), payload.getAvailable());
        Set<Author> authors = payload.getAuthors()
                .stream()
                .map(authorId ->
                        authorRepository.findById(authorId)
                                .orElseThrow(() -> new IllegalStateException("Can not find author with given id: " + authorId))
                ).collect(Collectors.toSet());
        updateBookAuthors(book, authors);

        return book;
    }

    private void updateBookAuthors(Book book, Set<Author> authors) {
        book.removeAuthors();
        authors.forEach(book::addAuthor);
        log.info(book.toString());
    }

    @Override
    public ResponseEntity removeById(Long id) {
        Optional<Book> book = repository.findById(id);
        if (book.isPresent()) {
            repository.deleteById(id);
        }
        return ResponseEntity.noContent().build();
    }

    @Override
    @Transactional
    public UpdateBookResponse updateBook(Long id, UpdateBookPayload payload) {
        return repository.findById(id)
                .map(book -> {
                    Book updatedBook = toUpdatedBook(payload, book);
                    updatedBook.setPut(true);
                    repository.save(updatedBook);
                    return UpdateBookResponse.success(updatedBook.getId());
                })
                .orElseGet(() -> UpdateBookResponse.failure("Can not find book with id: " + id));
    }

    private Book toUpdatedBook(UpdateBookPayload payload, Book book) {
        Set<Author> authors = payload.getAuthors()
                .stream()
                .map(authorId -> authorRepository.findById(authorId)
                        .orElseThrow(() -> new IllegalStateException("Can not find author with given id: " + authorId))
                ).collect(Collectors.toSet());


        book.setTitle(payload.getTitle());
        book.setYear(payload.getYear());
        book.setPrice(payload.getPrice());
        book.setAvailable(payload.getAvailable());
        updateBookAuthors(book, authors);

        return book;
    }

    @Override
    public CreateBookResponse updateBookCover(Long id, UpdateBookCoverPayload payload) {
        return repository.findById(id)
                .map(book -> {
                    if (payload.getFilename().isBlank()) {
                        throw new IllegalArgumentException("Invalid file");
                    }
                    Upload savedUpload = upload.save(new UpdateBookCoverPayload(payload.getFile(), payload.getContentType(), payload.getFilename()));
                    book.setCoverId(savedUpload.getId());
                    repository.save(book);

                    int timeout = new SplittableRandom().nextInt(1000, 8000);
                    try {
                        Thread.sleep(timeout);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }

                    return CreateBookResponse.success(book.getId());
                })
                .orElseGet(() -> CreateBookResponse.failure("Can not find a book with id: " + id));
    }

    @Override
    public PartialUpdateBookResponse partialUpdateBook(Long id, Map<Object, Object> fields) {

        Optional<Book> existingBook = repository.findById(id);
        if (existingBook.isEmpty()) {
            return PartialUpdateBookResponse.failure("Can not find a book with id: " + id);
        }

        AtomicBoolean isError = new AtomicBoolean(false);
        StringBuilderPlus errors = new StringBuilderPlus();

        return existingBook
                .map(book -> {
                    fields.forEach((key, value) -> {
                        Field field = ReflectionUtils.findField(Book.class, (String) key);
                        field.setAccessible(true);

                        // ID
                        if (field.getName().equals("id")) {
                            // ignore id
                        }
                        // PRICE
                        else if (field.getName().equals("price")) {
                            if (value == null) {
                                isError.set(true);
                                errors.appendLine("price incorrect input data");
                                return;
                            }

                            // TODO - BUG 5 - (PATCH /books/:id) - Można edytować cenę książki z niepoprawnym formatem.
                            //  Nie ma sprawdzenia validacji ceny na dwa miejsca po przecinku.
                            //  Cena może być ustawiona na "price": 9.9999911
                            if (String.valueOf(value).equals("null")) {
                                isError.set(true);
                                errors.appendLine("price incorrect input data");
                                return;
                            }
                            if (String.valueOf(value).isBlank()) {
                                isError.set(true);
                                errors.appendLine("price incorrect input data");
                                return;
                            }
                            if (Double.valueOf(String.valueOf(value)) < 1) {
                                isError.set(true);
                                errors.appendLine("price incorrect input data");
                                return;
                            }
                            if (Double.valueOf(String.valueOf(value)) > 1000) {
                                isError.set(true);
                                errors.appendLine("price incorrect input data");
                                return;
                            }
                            ReflectionUtils.setField(field, book, new BigDecimal(String.valueOf(value)));
                        }
                        // AVAILABLE
                        else if (field.getName().equals("available")) {
                            if (value == null) {
                                isError.set(true);
                                errors.appendLine("available incorrect input data");
                                return;
                            }
                            if (String.valueOf(value).equals("null")) {
                                isError.set(true);
                                errors.appendLine("available incorrect input data");
                                return;
                            }
                            if (String.valueOf(value).isBlank()) {
                                isError.set(true);
                                errors.appendLine("available incorrect input data");
                                return;
                            }

                            if (value.getClass() != Integer.class) {
                                isError.set(true);
                                errors.appendLine("available incorrect input data");
                                return;
                            }

                            if (Integer.valueOf(String.valueOf(value)) < 1) {
                                isError.set(true);
                                errors.appendLine("available incorrect input data");
                                return;
                            }
                            if (Integer.valueOf(String.valueOf(value)) > 10000) {
                                isError.set(true);
                                errors.appendLine("available incorrect input data");
                                return;
                            }
                            ReflectionUtils.setField(field, book, new Integer(String.valueOf(value)));
                        }
                        else if (field.getName().equals("year")) {
                            if (isNullOrEmpty(value)) {
                                isError.set(true);
                                errors.appendLine("year incorrect input data");
                                return;
                            }
                            if (value.getClass() != Integer.class) {
                                isError.set(true);
                                errors.appendLine("year incorrect input data");
                                return;
                            }
                            if (Integer.parseInt(value.toString()) < 1900) {
                                isError.set(true);
                                errors.appendLine("year incorrect input data");
                                return;
                            }
                            ReflectionUtils.setField(field, book, value);

                        } else if (field.getName().equals("title")) {
                            if (isNullOrEmpty(value)) {
                                isError.set(true);
                                errors.appendLine("title incorrect input data");
                                return;
                            }
                            if (value.toString().startsWith(" ") || value.toString().endsWith(" ")) {
                                isError.set(true);
                                errors.appendLine("title incorrect input data");
                                return;
                            }
                            if (value.getClass() != String.class) {
                                isError.set(true);
                                errors.appendLine("title incorrect input data");
                                return;
                            }
                            ReflectionUtils.setField(field, book, value);

                        } else if (field.getName().equals("authors")) {
                            if (value == null || value.toString().equals("[]")) {
                                isError.set(true);
                                errors.appendLine("authors incorrect input data");
                                return;
                            }
                            if (value.getClass() != ArrayList.class) {
                                isError.set(true);
                                errors.appendLine("authors incorrect input data");
                                return;
                            }

                            Set<Author> authors = ((ArrayList<Integer>) value).stream()
                                    .map(authorId -> authorRepository.findById(authorId.longValue())
                                            .orElseThrow(() -> new IllegalStateException("Can not find author with given id: " + authorId))
                                    ).collect(Collectors.toSet());

                            ReflectionUtils.setField(field, book, authors);
                        } else {
                            ReflectionUtils.setField(field, book, value);
                        }
                    });

                    if (isError.get()) {
                        throw new IllegalArgumentException(errors.toString());
                    } else {
                        repository.save(book);
                    }

                    return PartialUpdateBookResponse.success(book.getId());

                })
                .orElseGet(() -> PartialUpdateBookResponse.failure("Can not find a book with id: " + id));
    }

    @Override
    public ResponseEntity removeBookCover(Long id) {
        repository.findById(id)
                .ifPresent(book -> {
                    if (book.getCoverId() != null) {
                        upload.removeById(book.getCoverId());
                        book.setCoverId(null);
                        repository.save(book);
                    }
                });

        return ResponseEntity.noContent().build();
    }
}
