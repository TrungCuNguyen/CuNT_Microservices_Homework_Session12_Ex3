package com.example.productservice.service;

import com.example.productservice.dto.request.ProductRequestDTO;
import com.example.productservice.dto.response.ProductResponseDTO;

import java.util.List;

public interface ProductService {

    ProductResponseDTO getProductById(Long id);

    ProductResponseDTO createProduct(ProductRequestDTO requestDTO);

    ProductResponseDTO updateProduct(Long id, ProductRequestDTO requestDTO);

    ProductResponseDTO purchaseProductFlashSale(Long productId, Integer quantity);

    void deleteProduct(Long id);

    List<ProductResponseDTO> getAllProducts();
}

