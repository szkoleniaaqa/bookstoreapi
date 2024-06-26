package pl.akademiaqa.bos.order.api.payload;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class UpdateStatusPayload {

    @NotBlank(message = "incorrect input data")
    String status;
}
