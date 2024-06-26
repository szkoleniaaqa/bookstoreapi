package pl.akademiaqa.bos.order.api.controller;

import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.*;
import pl.akademiaqa.bos.order.api.payload.CreateOrderPayload;
import pl.akademiaqa.bos.order.api.payload.UpdateStatusPayload;
import pl.akademiaqa.bos.order.domain.RichOrder;
import pl.akademiaqa.bos.order.service.port.IOrderService;
import pl.akademiaqa.bos.web.CreatedURI;

import javax.validation.Valid;
import java.net.URI;
import java.util.List;

@RestController
@AllArgsConstructor
@RequestMapping(value = "/orders")
public class OrdersController {

    private final IOrderService orders;

    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    public List<RichOrder> getAllOrders() {
        return orders.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<RichOrder> getOrderById(@PathVariable Long id) {
        return orders.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<Object> createOrder(@Valid @RequestBody CreateOrderPayload payload) {
        return orders.createOrder(payload)
                .handle(
                        orderId -> ResponseEntity.created(orderUri(orderId)).body(orders.findById(orderId)),
                        error -> ResponseEntity.badRequest().body(error)
                );
    }

    private URI orderUri(Long orderId) {
        return new CreatedURI("/" + orderId).uri();
    }

    @Secured({"ROLE_ADMIN"})
    @PatchMapping("/{id}/status")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<RichOrder> updateOrderStatus(@PathVariable Long id, @RequestBody UpdateStatusPayload payload) {
        return orders.updateOrderStatus(id, payload);
    }

    @Secured({"ROLE_ADMIN"})
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    // TODO - BUG 9 (DELETE /orders/:id) - Usunięcie zamówienia w statusie NEW nie przywraca dostępności książek
    public ResponseEntity deleteOrder(@PathVariable Long id) {
        return orders.deleteById(id);
    }
}
