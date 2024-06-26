package pl.akademiaqa.bos.books.service;

import com.opencsv.bean.CsvBindByName;
import com.opencsv.bean.CsvToBean;
import com.opencsv.bean.CsvToBeanBuilder;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import pl.akademiaqa.bos.autors.db.AuthorJpaRepository;
import pl.akademiaqa.bos.autors.domain.Author;
import pl.akademiaqa.bos.books.api.payload.CreateBookPayload;
import pl.akademiaqa.bos.books.api.payload.UpdateBookCoverPayload;
import pl.akademiaqa.bos.books.domain.Book;
import pl.akademiaqa.bos.books.service.port.IBookService;
import pl.akademiaqa.bos.books.service.port.ICatalogInitializerService;
import pl.akademiaqa.bos.jpa.BaseEntity;
import pl.akademiaqa.bos.order.api.payload.CreateOrderItemPayload;
import pl.akademiaqa.bos.order.api.payload.CreateOrderPayload;
import pl.akademiaqa.bos.order.api.payload.CreateRecipientPayload;
import pl.akademiaqa.bos.order.api.response.CreateOrderResponse;
import pl.akademiaqa.bos.order.service.port.IOrderService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@AllArgsConstructor
@Slf4j
public class CatalogInitializerService implements ICatalogInitializerService {

    private final IBookService bookService;
    private final IOrderService orderService;
    private final AuthorJpaRepository authorRepository;
    private final RestTemplate restTemplate;


    @Override
    @Transactional
    public void initialize() {
        initData();
        placeOrder();
    }

    private void initData() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ClassPathResource("books.csv").getInputStream()))) {
            CsvToBean<CsvBook> build = new CsvToBeanBuilder<CsvBook>(reader)
                    .withType(CsvBook.class)
                    .withIgnoreLeadingWhiteSpace(true)
                    .build();

            build.stream()
                    .forEach(this::initBook);

        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse CSV file", e);
        }
    }

    private void initBook(CsvBook csvBook) {
        Set<Long> authors = Arrays.stream(csvBook.authors.split(","))
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .map(this::getOrCreateAuthor)
                .map(BaseEntity::getId)
                .collect(Collectors.toSet());


        CreateBookPayload bookPayload = new CreateBookPayload(
                csvBook.title.trim(),
                authors,
                csvBook.year,
                csvBook.amount,
                100);

        bookService.createBook(bookPayload)
                .handle(
                        bookId -> {
                            ResponseEntity<byte[]> response = restTemplate.exchange(csvBook.thumbnail, HttpMethod.GET, null, byte[].class);
                            String contentType = response.getHeaders().getContentType().toString();
                            return bookService.updateBookCover(bookId, new UpdateBookCoverPayload(response.getBody(), contentType, "cover"));
                        },
                        error -> {
                            throw new IllegalStateException("Can not update book cover with given image: " + csvBook.thumbnail);
                        }
                );
    }

    private Author getOrCreateAuthor(String fullName) {
        List<String> name = Arrays.asList(fullName.replaceAll(",", "").split(" "));
        String firstName = name.get(0);
        StringBuilder lastName = new StringBuilder();
        name.stream().skip(1)
                .forEach((n) -> lastName.append(n));

        return authorRepository.findByFullNameIgnoreCase(firstName + " " + lastName)
                .orElseGet(() -> authorRepository.save(new Author(firstName, lastName.toString())));
    }

    //
    private void placeOrder() {
        Book effectiveJava = bookService.findOneByTitle("Effective Java").orElseThrow(() -> new IllegalStateException("Can not find a book"));
        Book puzzlers = bookService.findOneByTitle("Java Puzzlers").orElseThrow(() -> new IllegalStateException("Can not find a book"));

        CreateRecipientPayload recipient = CreateRecipientPayload
                .builder()
                .name("Jan Kowalski")
                .phone("745222111")
                .street("Sezamkowa 10")
                .city("Krakow")
                .zipCode("30-004")
                .email("janekkowalski@testmail.pl")
                .build();

        CreateOrderItemPayload item1 = CreateOrderItemPayload.builder()
                .bookId(effectiveJava.getId())
                .quantity(5)
                .build();

        CreateOrderItemPayload item2 = CreateOrderItemPayload.builder()
                .bookId(puzzlers.getId())
                .quantity(5)
                .build();

        CreateOrderPayload payload = CreateOrderPayload.builder()
                .item(item1)
                .item(item2)
                .recipient(recipient)
                .build();

        CreateOrderResponse createOrderResponse = orderService.createOrder(payload);

        Object result = createOrderResponse.handle(
                orderId -> "Created ORDER with id: " + orderId,
                error -> "Failed to created order: " + error
        );
        log.info(result.toString());
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class CsvBook {
        @CsvBindByName
        private String title;
        @CsvBindByName
        private String authors;
        @CsvBindByName
        private Integer year;
        @CsvBindByName
        private BigDecimal amount;
        @CsvBindByName
        private String thumbnail;
    }
}
