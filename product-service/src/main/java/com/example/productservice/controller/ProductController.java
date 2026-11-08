package com.example.productservice.controller;

import com.example.productservice.dto.request.ProductRequestDTO;
import com.example.productservice.dto.response.ApiResponse;
import com.example.productservice.dto.response.ProductResponseDTO;
import com.example.productservice.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    /**
     * Lấy chi tiết sản phẩm theo ID.
     * Có đo thời gian thực thi để kiểm chứng kết quả mong muốn:
     * - Lần đầu (Cache Miss - Database): ~200ms
     * - Các lần sau (Cache Hit - Redis): <10ms
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ProductResponseDTO>> getProductById(@PathVariable Long id) {
        long startTime = System.currentTimeMillis();

        ProductResponseDTO responseDTO = productService.getProductById(id);

        long responseTime = System.currentTimeMillis() - startTime;
        String source = responseTime >= 100 ? "DATABASE (Cache Miss)" : "REDIS_CACHE (Cache Hit)";

        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Response-Time-Ms", String.valueOf(responseTime));
        headers.add("X-Data-Source", source);

        ApiResponse<ProductResponseDTO> apiResponse = ApiResponse.success(
                responseDTO,
                "Lấy thông tin sản phẩm thành công",
                responseTime,
                source
        );

        return ResponseEntity.ok()
                .headers(headers)
                .body(apiResponse);
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ProductResponseDTO>> createProduct(@Valid @RequestBody ProductRequestDTO requestDTO) {
        ProductResponseDTO responseDTO = productService.createProduct(requestDTO);
        return new ResponseEntity<>(
                ApiResponse.created(responseDTO, "Tạo sản phẩm mới thành công"),
                HttpStatus.CREATED
        );
    }

    /**
     * API đặt mua sản phẩm trong đợt Flash Sale áp dụng Redisson Distributed Lock.
     * Đảm bảo chỉ đúng số lượng người mua bằng số lượng tồn kho thực tế thành công,
     * còn lại nhận thông báo "Sản phẩm đã hết hàng".
     */
    @PostMapping("/{id}/purchase")
    public ResponseEntity<ApiResponse<ProductResponseDTO>> purchaseProductFlashSale(
            @PathVariable Long id,
            @RequestParam(required = false, defaultValue = "1") Integer quantity) {
        ProductResponseDTO responseDTO = productService.purchaseProductFlashSale(id, quantity);
        return ResponseEntity.ok(ApiResponse.success(
                responseDTO,
                "Đặt mua sản phẩm thành công! Tồn kho còn lại: " + responseDTO.getStockQuantity()
        ));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ProductResponseDTO>> updateProduct(
            @PathVariable Long id,
            @Valid @RequestBody ProductRequestDTO requestDTO) {
        ProductResponseDTO responseDTO = productService.updateProduct(id, requestDTO);
        return ResponseEntity.ok(ApiResponse.success(responseDTO, "Cập nhật sản phẩm và xóa Cache Redis thành công"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteProduct(@PathVariable Long id) {
        productService.deleteProduct(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Xóa sản phẩm và xóa Cache Redis thành công"));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ProductResponseDTO>>> getAllProducts() {
        List<ProductResponseDTO> list = productService.getAllProducts();
        return ResponseEntity.ok(ApiResponse.success(list, "Lấy danh sách sản phẩm thành công"));
    }
}

