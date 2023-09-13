import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.auth0.jwk.JwkException;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.kumuluz.ee.discovery.annotations.DiscoverService;
import com.kumuluz.ee.logs.cdi.Log;
import com.kumuluz.ee.logs.cdi.LogParams;
import io.opentracing.Span;
import io.opentracing.Tracer;
import org.eclipse.microprofile.faulttolerance.*;
import org.eclipse.microprofile.jwt.Claim;
import org.eclipse.microprofile.jwt.ClaimValue;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.metrics.Histogram;
import org.eclipse.microprofile.metrics.annotation.*;
import org.eclipse.microprofile.opentracing.Traced;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

@Path("/cart")
@Log(LogParams.METRICS)
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class CartResource {

    @Inject
    private ConfigProperties configProperties;

    @Inject
    private Tracer tracer;

    @Inject
    @Claim("cognito:groups")
    private ClaimValue<Set<String>> groups;

    @Inject
    private JsonWebToken jwt;

    @Inject
    @Claim("sub")
    private ClaimValue<Optional<String>> optSubject;

    @Inject
    @DiscoverService(value = "catalog-service", environment = "dev", version = "1.0.0")
    private Optional<URL> productCatalogUrl;

    @Inject
    @DiscoverService(value = "orders-service", environment = "dev", version = "1.0.0")
    private Optional<URL> ordersUrl;

    private static final int PAGE_SIZE = 3;
    private DynamoDbClient dynamoDB;
    private static final Logger LOGGER = Logger.getLogger(CartResource.class.getName());

    private volatile String currentRegion;
    private volatile String currentTableName;
    private void checkAndUpdateDynamoDbClient() {
        String newRegion = configProperties.getDynamoRegion();
        if (!newRegion.equals(currentRegion)) {
            try {
                this.dynamoDB = DynamoDbClient.builder()
                        .region(Region.of(newRegion))
                        .build();
                currentRegion = newRegion;
            } catch (Exception e) {
                LOGGER.severe("Error while creating DynamoDB client: " + e.getMessage());
                throw new WebApplicationException("Error while creating DynamoDB client: " + e.getMessage(), e, Response.Status.INTERNAL_SERVER_ERROR);
            }
        }
        currentTableName = configProperties.getTableName();
    }

    @Inject
    @Metric(name = "getProductsHistogram distribution of execution time")
    Histogram getProductsHistogram;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Counted(name = "getCartCount", description = "Count of getCart calls")
    @Timed(name = "getCartTime", description = "Time taken to fetch a cart")
    @Metered(name = "getCartMetered", description = "Rate of getCart calls")
    @ConcurrentGauge(name = "getCartConcurrent", description = "Concurrent getCart calls")
    @Timeout(value = 20, unit = ChronoUnit.SECONDS) // Timeout after 20 seconds
    @Retry(maxRetries = 3) // Retry up to 3 times
    @Fallback(fallbackMethod = "getCartFallback") // Fallback method if all retries fail
    @CircuitBreaker(requestVolumeThreshold = 4) // Use circuit breaker after 4 failed requests
    @Bulkhead(5) // Limit concurrent calls to 5
    @Traced
    public Response getCart(@QueryParam("page") Integer page) {

        if (jwt == null) {
            LOGGER.info("Unauthorized: only authenticated users can view his/hers cart.");
            return Response.ok("Unauthorized: only authenticated users can view his/hers cart.").build();
        }
        String userId = optSubject.getValue().orElse("default_value");

        Span span = tracer.buildSpan("getCart").start();
        span.setTag("userId", userId);
        Map<String, Object> logMap = new HashMap<>();
        logMap.put("event", "getCart");
        logMap.put("value", userId);
        logMap.put("groups", groups.getValue());
        logMap.put("email", jwt.getClaim("email"));
        span.log(logMap);
        LOGGER.info("getCart method called");
        checkAndUpdateDynamoDbClient();


        Map<String, AttributeValue> expressionAttributeValues = new HashMap<>();
        expressionAttributeValues.put(":v_id", AttributeValue.builder().s(userId).build());

        QueryRequest queryRequest = QueryRequest.builder()
                .tableName(currentTableName)
                .keyConditionExpression("UserId = :v_id")
                .expressionAttributeValues(expressionAttributeValues)
                .build();

        try {
            QueryResponse queryResponse = dynamoDB.query(queryRequest);
            List<Map<String, AttributeValue>> itemsList = queryResponse.items();

            if (itemsList.isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND).entity("No items found in cart.").build();
            }
            Map<String, AttributeValue> userCart = itemsList.get(0);
            List<Map<String, String>> items = ResponseTransformer.transformCartItems(Collections.singletonList(userCart));
            List<Map<String, String>> products = new Gson().fromJson(items.get(0).get("products"), new TypeToken<List<Map<String, String>>>() {
            }.getType());
            int totalPages = (int) Math.ceil((double) products.size() / PAGE_SIZE);
            if (page == null) {
                page = 1;
            }

            int start = (page - 1) * PAGE_SIZE;
            int end = Math.min(start + PAGE_SIZE, products.size());
            List<Map<String, String>> pagedProducts = products.subList(start, end);

            Map<String, Object> responseBody = new HashMap<>();
            responseBody.put("products", pagedProducts);
            responseBody.put("totalPages", totalPages);
            responseBody.put("totalPrice", items.get(0).get("TotalPrice"));

//            span.setTag("completed", true);
            return Response.ok(responseBody).build();
        } catch (DynamoDbException e) {
            LOGGER.log(Level.SEVERE, "Error while getting cart for user " + userId, e);
//            span.setTag("error", true);
            throw new WebApplicationException("Error while getting cart. Please try again later.", e, Response.Status.INTERNAL_SERVER_ERROR);
        } finally {
//            span.finish();
        }
    }
    public Response getCartFallback(@QueryParam("page") Integer page) {
        LOGGER.info("Fallback activated: Unable to fetch cart at the moment for token: " + optSubject.getValue().orElse("default_value"));
        Map<String, String> response = new HashMap<>();
        response.put("description", "Unable to fetch cart at the moment. Please try again later.");
        return Response.ok(response).build();
    }


    @POST
    @Path("/add")
    @Counted(name = "addProductToCartCount", description = "Count of addProductToCart calls")
    @Timed(name = "addProductToCartTime", description = "Time taken to add a product to a cart")
    @Metered(name = "addProductToCartMetered", description = "Rate of addProductToCart calls")
    @ConcurrentGauge(name = "addProductToCartConcurrent", description = "Concurrent addProductToCart calls")
    @Timeout(value = 20, unit = ChronoUnit.SECONDS) // Timeout after 20 seconds
    @Retry(maxRetries = 3) // Retry up to 3 times
    @Fallback(fallbackMethod = "addProductToCartFallback") // Fallback method if all retries fail
    @CircuitBreaker(requestVolumeThreshold = 4) // Use circuit breaker after 4 failed requests
    @Bulkhead(5) // Limit concurrent calls to 5
    @Traced
    public Response addToCart(CartItem cartItem) {
        if (jwt == null) {
            LOGGER.info("Unauthorized: only authenticated users can add products to his/hers cart.");
            return Response.status(Response.Status.UNAUTHORIZED).entity("Unauthorized: only authenticated users can add products to his/hers cart.").build();
        }
        String userId = optSubject.getValue().orElse("default_value");

        Span span = tracer.buildSpan("addToCart").start();
        span.setTag("userId", userId);
        span.setTag("productId", cartItem.getProductId());
        Map<String, Object> logMap = new HashMap<>();
        logMap.put("event", "addToCart");
        logMap.put("value", userId);
        logMap.put("productId", cartItem.getProductId());
        logMap.put("groups", groups.getValue());
        logMap.put("email", jwt.getClaim("email"));
        span.log(logMap);
        LOGGER.info("addToCart method called");
        checkAndUpdateDynamoDbClient();

        // Extract the productId from the cartItem
        String productId = cartItem.getProductId();
        String quantity = cartItem.getQuantity();

        // Construct the key for the item
        Map<String, AttributeValue> key = new HashMap<>();
        key.put("UserId", AttributeValue.builder().s(userId).build());

        // Define a GetItemRequest
        GetItemRequest getItemRequest = GetItemRequest.builder()
                .tableName(currentTableName)
                .key(key)
                .build();

        try {
            // Send the GetItemRequest
            GetItemResponse getItemResponse = dynamoDB.getItem(getItemRequest);

            // Check if the user exists in the table, if not, create an empty OrderList and initialize TotalPrice
            if (getItemResponse.item() == null || !getItemResponse.item().containsKey("OrderList") || !getItemResponse.item().containsKey("TotalPrice")) {
                Map<String, String> expressionAttributeNames = new HashMap<>();
                expressionAttributeNames.put("#O", "OrderList");
                expressionAttributeNames.put("#T", "TotalPrice");

                Map<String, AttributeValue> expressionAttributeValues = new HashMap<>();
                expressionAttributeValues.put(":ol", AttributeValue.builder().s(";").build()); // create an empty OrderList
                expressionAttributeValues.put(":t", AttributeValue.builder().n("0.0").build()); // initialize TotalPrice

                UpdateItemRequest updateItemRequest = UpdateItemRequest.builder()
                        .tableName(currentTableName)
                        .key(key)
                        .updateExpression("SET #O = :ol, #T = :t")
                        .expressionAttributeNames(expressionAttributeNames)
                        .expressionAttributeValues(expressionAttributeValues)
                        .build();
                dynamoDB.updateItem(updateItemRequest);

                // Re-fetch the item after creating an empty OrderList and initializing TotalPrice
                getItemResponse = dynamoDB.getItem(getItemRequest);
            }
            // Get the OrderList
            String orderListStr = getItemResponse.item().get("OrderList").s();
            if (!orderListStr.endsWith(";")) {
                orderListStr += ";";
            }
            String[] orderList = orderListStr.split(";");

            // Parse the OrderList and look for the product
            boolean found = false;
            for (int i = 0; i < orderList.length; i++) {
                String[] parts = orderList[i].split(":");
                if (parts[0].equals(productId)) {
                    // Found the product, update the quantity
                    parts[1] = String.valueOf(Integer.parseInt(quantity));
                    orderList[i] = String.join(":", parts);
                    found = true;
                    break;
                }
            }

            // If the product was not found, add it to the end of the OrderList
            if (!found) {
                String[] newOrderList = Arrays.copyOf(orderList, orderList.length + 1);
                newOrderList[newOrderList.length - 1] = productId + ":" + quantity;
                orderList = newOrderList;
            }

            // Convert the OrderList back to a string
            orderListStr = String.join(";", orderList);

            // Prepare the expression attributes
            Map<String, String> expressionAttributeNames = new HashMap<>();
            expressionAttributeNames.put("#O", "OrderList");
            Map<String, AttributeValue> expressionAttributeValues = new HashMap<>();
            expressionAttributeValues.put(":ol", AttributeValue.builder().s(orderListStr).build());
            UpdateItemRequest updateItemRequest = UpdateItemRequest.builder()
                    .tableName(currentTableName)
                    .key(key)
                    .updateExpression("SET #O = :ol")
                    .expressionAttributeNames(expressionAttributeNames)
                    .expressionAttributeValues(expressionAttributeValues)
                    .build();
            dynamoDB.updateItem(updateItemRequest);

            double totalPrice = 0.0;
            Gson gson = new Gson();
            for (String order : orderList) {
                String[] parts = order.split(":");
                String productIdOrder = parts[0];
                int quantityOrder = Integer.parseInt(parts[1]);
                // Call the product catalog microservice to get the product details
                if (productCatalogUrl.isPresent()) {
                    ProductCatalogApi api = RestClientBuilder.newBuilder()
                            .baseUrl(new URL(productCatalogUrl.get().toString()))
                            .build(ProductCatalogApi.class);

                    Response response = api.getProduct(productIdOrder);

                    if (response.getStatus() != Response.Status.OK.getStatusCode()) {
                        throw new RuntimeException("Failed : HTTP error code : " + response.getStatus());
                    }

                    String json = response.readEntity(String.class);
                    Product product = gson.fromJson(json, Product.class);

                    // Get the price of the product
                    double productPrice = product.getPrice();

                    // Add the total price of this product to the total price
                    totalPrice += productPrice * quantityOrder;
                }
            }


                // Update the TotalPrice in the CartDB table
                Map<String, String> expressionAttributeNamesTotal = new HashMap<>();
                expressionAttributeNamesTotal.put("#T", "TotalPrice");

                Map<String, AttributeValue> expressionAttributeValuesTotal = new HashMap<>();
                expressionAttributeValuesTotal.put(":t", AttributeValue.builder().n(String.valueOf(totalPrice)).build());

                UpdateItemRequest updateTotalPriceRequest = UpdateItemRequest.builder()
                        .tableName(currentTableName)
                        .key(key)
                        .updateExpression("SET #T = :t")
                        .expressionAttributeNames(expressionAttributeNamesTotal)
                        .expressionAttributeValues(expressionAttributeValuesTotal)
                        .build();

                dynamoDB.updateItem(updateTotalPriceRequest);

            // Fetch the updated item
            GetItemResponse updatedItemResponse = dynamoDB.getItem(getItemRequest);

            span.setTag("completed", true);
            return Response.ok(ResponseTransformer.transformItem(updatedItemResponse.item())).build();
        } catch (DynamoDbException | MalformedURLException e) {
            LOGGER.log(Level.SEVERE, "Error while adding product to cart for user " + userId, e);
            span.setTag("error", true);
            throw new WebApplicationException("Error while adding product to cart. Please try again later.", e, Response.Status.INTERNAL_SERVER_ERROR);
        } finally {
            span.finish();
        }
    }
    public Response addProductToCartFallback(CartItem cartItem) {
        LOGGER.info("Fallback activated: Unable to add product to cart at the moment for token: " + optSubject.getValue().orElse("default_value"));
        Map<String, String> response = new HashMap<>();
        response.put("description", "Unable to add product to cart at the moment. Please try again later.");
        return Response.ok(response).build();
    }



    @DELETE
    @Path("/{productId}")
    @Counted(name = "deleteProductFromCartCount", description = "Count of deleteProductFromCart calls")
    @Timed(name = "deleteProductFromCartTime", description = "Time taken to delete a product from a cart")
    @Metered(name = "deleteProductFromCartMetered", description = "Rate of deleteProductFromCart calls")
    @ConcurrentGauge(name = "deleteProductFromCartConcurrent", description = "Concurrent deleteProductFromCart calls")
    @Timeout(value = 20, unit = ChronoUnit.SECONDS) // Timeout after 20 seconds
    @Retry(maxRetries = 3) // Retry up to 3 times
    @Fallback(fallbackMethod = "deleteProductFromCartFallback") // Fallback method if all retries fail
    @CircuitBreaker(requestVolumeThreshold = 4) // Use circuit breaker after 4 failed requests
    @Bulkhead(5) // Limit concurrent calls to 5
    @Traced
    public Response deleteFromCart(@PathParam("productId") String productId) {
        if (jwt == null) {
            LOGGER.info("Unauthorized: only authenticated users can delete products from his/hers cart.");
            return Response.status(Response.Status.UNAUTHORIZED).entity("Unauthorized: only authenticated users can delete products from his/hers cart.").build();
        }
        String userId = optSubject.getValue().orElse("default_value");

        Span span = tracer.buildSpan("deleteFromCart").start();
        span.setTag("userId", userId);
        span.setTag("productId", productId);
        Map<String, Object> logMap = new HashMap<>();
        logMap.put("event", "deleteFromCart");
        logMap.put("value", userId);
        logMap.put("productId", productId);
        logMap.put("groups", groups.getValue());
        logMap.put("email", jwt.getClaim("email"));
        span.log(logMap);
        LOGGER.info("deleteFromCart method called");
        checkAndUpdateDynamoDbClient();

        // Construct the key for the item
        Map<String, AttributeValue> key = new HashMap<>();
        key.put("UserId", AttributeValue.builder().s(userId).build());

        // Define a GetItemRequest
        GetItemRequest getItemRequest = GetItemRequest.builder()
                .tableName(currentTableName)
                .key(key)
                .build();

        try {
            // Send the GetItemRequest
            GetItemResponse getItemResponse = dynamoDB.getItem(getItemRequest);

            // Get the OrderList
            String orderListStr = getItemResponse.item().get("OrderList").s();
            double totalPrice = Double.parseDouble(getItemResponse.item().get("TotalPrice").n());
            // Split the OrderList by ";" and filter out the product to delete
            String[] orderList = orderListStr.split(";");
            StringBuilder updatedOrderListStr = new StringBuilder();
            int quantityToDelete = 0;
            for (String order : orderList) {
                String[] parts = order.split(":");
                if (parts[0].equals(productId)) {
                    quantityToDelete = Integer.parseInt(parts[1]);
                } else {
                    updatedOrderListStr.append(order).append(";");
                }
            }
            Gson gson = new Gson();
            ProductCatalogApi api = RestClientBuilder.newBuilder()
                    .baseUrl(new URL(productCatalogUrl.get().toString()))
                    .build(ProductCatalogApi.class);

            Response response = api.getProduct(productId);

            if (response.getStatus() != Response.Status.OK.getStatusCode()) {
                throw new RuntimeException("Failed : HTTP error code : " + response.getStatus());
            }

            String json = response.readEntity(String.class);
            Product product = gson.fromJson(json, Product.class);

            // Get the price of the product
            double productPrice = product.getPrice();

            // Add the total price of this product to the total price
            double updatedTotalPrice = totalPrice - productPrice * quantityToDelete;

            // Create the updated OrderList and TotalPrice attributes
            AttributeValue updatedOrderListAttr = AttributeValue.builder().s(updatedOrderListStr.toString()).build();
            AttributeValue updatedTotalPriceAttr = AttributeValue.builder().n(String.valueOf(updatedTotalPrice)).build();

            // Define the updated item attributes
            Map<String, AttributeValueUpdate> updatedItemAttrs = new HashMap<>();
            updatedItemAttrs.put("OrderList", AttributeValueUpdate.builder().value(updatedOrderListAttr).action(AttributeAction.PUT).build());
            updatedItemAttrs.put("TotalPrice", AttributeValueUpdate.builder().value(updatedTotalPriceAttr).action(AttributeAction.PUT).build());

            // Define the UpdateItemRequest
            UpdateItemRequest updateItemRequest = UpdateItemRequest.builder()
                    .tableName(currentTableName)
                    .key(key)
                    .attributeUpdates(updatedItemAttrs)
                    .build();

            // Update the item
            dynamoDB.updateItem(updateItemRequest);
            // Create a response map
            Map<String, Object> responseBody = new HashMap<>();
            responseBody.put("TotalPrice", updatedTotalPrice);
            responseBody.put("message", "Product deleted from cart successfully");
            span.setTag("completed", true);
            return Response.ok(responseBody).build();
        } catch (DynamoDbException | MalformedURLException e) {
            LOGGER.log(Level.SEVERE, "Error while deleting product from cart for user " + userId, e);
            span.setTag("error", true);
            throw new WebApplicationException("Error while deleting product from cart. Please try again later.", e, Response.Status.INTERNAL_SERVER_ERROR);
        } finally {
            span.finish();
        }
    }
    public Response deleteProductFromCartFallback(@PathParam("productId") String productId) {
        LOGGER.info("Fallback activated: Unable to delete product from cart at the moment for token: " + optSubject.getValue().orElse("default_value"));
        Map<String, String> response = new HashMap<>();
        response.put("description", "Unable to delete product from cart at the moment. Please try again later.");
        return Response.ok(response).build();
    }

    @DELETE
    @Counted(name = "deleteCartCount", description = "Count of deleteCart calls")
    @Timed(name = "deleteCartTime", description = "Time taken to delete a cart")
    @Metered(name = "deleteCartMetered", description = "Rate of deleteCart calls")
    @ConcurrentGauge(name = "deleteCartConcurrent", description = "Concurrent deleteCart calls")
    @Timeout(value = 20, unit = ChronoUnit.SECONDS) // Timeout after 20 seconds
    @Retry(maxRetries = 3) // Retry up to 3 times
    @Fallback(fallbackMethod = "deleteCartFallback") // Fallback method if all retries fail
    @CircuitBreaker(requestVolumeThreshold = 4) // Use circuit breaker after 4 failed requests
    @Bulkhead(5) // Limit concurrent calls to 5
    @Traced
    public Response deleteFromCart() {
        if (jwt == null) {
            LOGGER.info("Unauthorized: only authenticated users can delete his/hers cart.");
            return Response.status(Response.Status.UNAUTHORIZED).entity("Unauthorized: only authenticated users can delete his/hers cart.").build();
        }
        String userId = optSubject.getValue().orElse("default_value");

        Span span = tracer.buildSpan("deleteFromCart").start();
        span.setTag("userId", userId);
        Map<String, Object> logMap = new HashMap<>();
        logMap.put("event", "deleteFromCart");
        logMap.put("value", userId);
        logMap.put("groups", groups.getValue());
        logMap.put("email", jwt.getClaim("email"));
        span.log(logMap);
        LOGGER.info("deleteFromCart method called");
        checkAndUpdateDynamoDbClient();

        // Construct the key for the item
        Map<String, AttributeValue> key = new HashMap<>();
        key.put("UserId", AttributeValue.builder().s(userId).build());

        // Define a DeleteItemRequest
        DeleteItemRequest deleteItemRequest = DeleteItemRequest.builder()
                .tableName(currentTableName)
                .key(key)
                .build();

        try {
            // Delete the item
            dynamoDB.deleteItem(deleteItemRequest);

            // Create a response map
            Map<String, Object> responseBody = new HashMap<>();
            responseBody.put("message", "Cart deleted successfully");
            span.setTag("completed", true);
            return Response.ok(responseBody).build();
        } catch (DynamoDbException e) {
            LOGGER.log(Level.SEVERE, "Error while deleting cart for user " + userId, e);
            span.setTag("error", true);
            throw new WebApplicationException("Error while deleting cart. Please try again later.", e, Response.Status.INTERNAL_SERVER_ERROR);
        } finally {
            span.finish();
        }
    }
    public Response deleteCartFallback() {
        LOGGER.info("Fallback activated: Unable to delete cart at the moment for token: " + optSubject.getValue().orElse("default_value"));
        Map<String, String> response = new HashMap<>();
        response.put("description", "Unable to delete cart at the moment. Please try again later.");
        return Response.ok(response).build();
    }
}
