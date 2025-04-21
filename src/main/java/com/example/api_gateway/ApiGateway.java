package com.example.api_gateway;

import io.vertx.circuitbreaker.CircuitBreaker;
import io.vertx.circuitbreaker.CircuitBreakerOptions;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;

public class ApiGateway extends AbstractVerticle {
  @Override
  public void start(Promise<Void> startPromise) {

    WebClientOptions option = new WebClientOptions()
      .setConnectTimeout(5000);
    WebClient client = WebClient.create(vertx, option);

    Router router = Router.router(vertx);
    router.get("/aggregate").handler(context -> handleAggregate(context, client));

    vertx.createHttpServer()
      .requestHandler(router)
      .listen(8080)
      .onSuccess(server -> {
        startPromise.complete();
        System.out.println("Server started on port " + server.actualPort());
      })
      .onFailure(startPromise::fail);
  }


  private void handleAggregate(RoutingContext context, WebClient client) {

    CircuitBreaker breaker = CircuitBreaker.create("my-circuit-breaker", vertx,
      new CircuitBreakerOptions()
        .setMaxFailures(3)
        .setTimeout(2000)
        .setFallbackOnFailure(true)
        .setResetTimeout(10000)
    );

    Future<HttpResponse<Buffer>> postFuture = breaker.execute(promise ->
      client
        .getAbs("https://jsonplaceholder.typicode.com/posts/1")
        .timeout(5000)
        .send(ar -> {
            if (ar.succeeded()) {
              promise.complete(ar.result());
            } else {
              promise.fail(ar.cause());
            }
          }
        )
    );

    Future<HttpResponse<Buffer>> userFuture = breaker.execute(promise ->
      client
        .getAbs("https://jsonplaceholder.typicode.com/users/1")
        .timeout(5000)
        .send(ar -> {
            if (ar.succeeded()) {
              promise.complete(ar.result());
            } else {
              promise.fail(ar.cause());
            }
          }
        )
    );

    CompositeFuture.all(postFuture, userFuture).onComplete(ar -> {
      if (ar.succeeded()) {

        HttpResponse<Buffer> postResp = postFuture.result();
        HttpResponse<Buffer> userResp = userFuture.result();

        if (postResp.statusCode() != 200 || userResp.statusCode() != 200) {
          context.response().setStatusCode(500).end("One of the API responses failed.");
          return;
        }

        JsonObject postJson = postResp.bodyAsJsonObject();
        JsonObject userJson = userResp.bodyAsJsonObject();
        JsonObject result = new JsonObject()
          .put("post_title", postJson.getString("title"))
          .put("author_name", userJson.getString("name"));

        context.response()
          .putHeader("Content-Type", "application/json")
          .end(result.encodePrettily());

      } else {
        System.out.println("Composite Future Failed: " + ar.cause().getMessage());
        context.response().setStatusCode(500)
          .putHeader("Content-Type", "application/json")
          .end(new JsonObject()
            .put("error", "API call failed or circuit breaker opened")
            .put("message", ar.cause().getMessage())
            .encodePrettily()
          );
      }
    });
  }
}
