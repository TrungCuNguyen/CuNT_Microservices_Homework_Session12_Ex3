package com.example.productservice;

import com.example.productservice.config.TestCacheConfig;
import com.example.productservice.entity.Product;
import com.example.productservice.exception.OutOfStockException;
import com.example.productservice.repository.ProductRepository;
import com.example.productservice.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestCacheConfig.class)
class ProductFlashSaleConcurrencyTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductRepository productRepository;

    private Long flashSaleProductId;

    @BeforeEach
    void setUp() {
        productRepository.deleteAll();

        // Chuẩn bị 1 sản phẩm Flash Sale DUY NHẤT CÒN 1 MÓN TRONG KHO theo đúng mô tả đề bài
        Product flashSaleProduct = productRepository.save(Product.builder()
                .name("Tai nghe AirPods Pro 2 Flash Sale 1k")
                .description("Số lượng siêu giới hạn chỉ 1 chiếc")
                .imageUrl("https://example.com/airpods.jpg")
                .price(new BigDecimal("1000"))
                .stockQuantity(1) // Tồn kho chỉ còn 1 chiếc
                .build());

        flashSaleProductId = flashSaleProduct.getId();
    }

    @Test
    @DisplayName("Flash Sale: 100 người cùng bấm mua 1 sản phẩm tồn kho 1 -> Đúng 1 người thành công, 99 người nhận 'Sản phẩm đã hết hàng'")
    void testFlashSaleConcurrencyWith100Users() throws InterruptedException {
        int numberOfUsers = 100;
        ExecutorService executor = Executors.newFixedThreadPool(30);

        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch doneSignal = new CountDownLatch(numberOfUsers);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger outOfStockCount = new AtomicInteger(0);
        AtomicInteger otherErrorCount = new AtomicInteger(0);

        for (int i = 0; i < numberOfUsers; i++) {
            executor.submit(() -> {
                try {
                    // Tất cả 100 threads cùng chờ lệnh xuất phát để đồng loạt gửi request
                    startSignal.await();

                    productService.purchaseProductFlashSale(flashSaleProductId, 1);
                    successCount.incrementAndGet();

                } catch (OutOfStockException e) {
                    if ("Sản phẩm đã hết hàng".equals(e.getMessage())) {
                        outOfStockCount.incrementAndGet();
                    } else {
                        otherErrorCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    otherErrorCount.incrementAndGet();
                } finally {
                    doneSignal.countDown();
                }
            });
        }

        // Bắn súng xuất phát: 100 threads đồng loạt tranh chấp mua hàng
        startSignal.countDown();

        // Chờ toàn bộ 100 threads hoàn tất
        doneSignal.await();
        executor.shutdown();

        // KIỂM CHỨNG KẾT QUẢ MONG MUỐN
        // 1. Đúng duy nhất 1 người mua thành công
        assertEquals(1, successCount.get(), "Chỉ đúng 1 người mua thành công vì tồn kho ban đầu = 1");

        // 2. 99 người còn lại nhận thông báo 'Sản phẩm đã hết hàng'
        assertEquals(99, outOfStockCount.get(), "99 người còn lại phải nhận thông báo 'Sản phẩm đã hết hàng'");

        // 3. Không có lỗi ngoại lệ bất thường nào khác
        assertEquals(0, otherErrorCount.get(), "Không có lỗi bất thường");

        // 4. Số lượng tồn kho trong Database sau cùng phải bằng 0 (không bị âm hoặc race condition)
        Product finalProduct = productRepository.findById(flashSaleProductId).orElseThrow();
        assertEquals(0, finalProduct.getStockQuantity(), "Tồn kho cuối cùng trong Database phải bằng đúng 0");
    }
}
