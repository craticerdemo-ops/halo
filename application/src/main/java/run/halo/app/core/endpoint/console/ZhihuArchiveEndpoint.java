package run.halo.app.core.endpoint.console;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;
import static org.springdoc.core.fn.builders.requestbody.Builder.requestBodyBuilder;

import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Validator;
import java.security.Principal;
import lombok.AllArgsConstructor;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.SinglePage;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.ListResult;
import run.halo.app.zhihu.ZhihuArchiveCreateRequest;
import run.halo.app.zhihu.ZhihuArchiveService;

/**
 * Console endpoint for managing Zhihu archive snapshots.
 *
 * <p>Requests reach this endpoint only after the {@code /apis/**} security chain enforced by
 * {@code WebServerSecurityConfig} has authenticated the caller and the authorization manager has matched the request
 * against the rules declared in {@code role-template-zhihu-archives.yaml}.
 *
 * @since 2.22.0
 */
@Component
@AllArgsConstructor
public class ZhihuArchiveEndpoint implements CustomEndpoint {

    private static final int DEFAULT_PAGE = 1;

    private static final int DEFAULT_SIZE = 20;

    private static final int MAX_SIZE = 100;

    private final ZhihuArchiveService zhihuArchiveService;

    private final Validator validator;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        var tag = "ZhihuArchiveV1alpha1Console";
        return SpringdocRouteBuilder.route()
                .GET(
                        "zhihu-archives",
                        this::listZhihuArchives,
                        builder -> builder.operationId("ListZhihuArchives")
                                .description("List Zhihu archive snapshots, newest first, with in-memory pagination.")
                                .tag(tag)
                                .parameter(parameterBuilder()
                                        .name("page")
                                        .description("Page number, starting from 1.")
                                        .in(ParameterIn.QUERY)
                                        .required(false)
                                        .implementation(Integer.class))
                                .parameter(parameterBuilder()
                                        .name("size")
                                        .description("Page size, between 1 and 100.")
                                        .in(ParameterIn.QUERY)
                                        .required(false)
                                        .implementation(Integer.class))
                                .response(responseBuilder()
                                        .implementation(ListResult.generateGenericClass(SinglePage.class))))
                .POST(
                        "zhihu-archives",
                        this::createZhihuArchive,
                        builder -> builder.operationId("CreateZhihuArchive")
                                .description("Create a Zhihu archive snapshot from a captured payload. Idempotent "
                                        + "for an unchanged payload of the same source.")
                                .tag(tag)
                                .requestBody(requestBodyBuilder()
                                        .required(true)
                                        .implementation(ZhihuArchiveCreateRequest.class))
                                .response(responseBuilder().implementation(SinglePage.class)))
                .DELETE(
                        "zhihu-archives/{name}",
                        this::deleteZhihuArchive,
                        builder -> builder.operationId("DeleteZhihuArchive")
                                .description(
                                        "Permanently delete a Zhihu archive snapshot and its versions, then rebuild "
                                                + "the archive index. Only pages created by the Zhihu archive feature can be "
                                                + "deleted through this endpoint.")
                                .tag(tag)
                                .parameter(parameterBuilder()
                                        .name("name")
                                        .description("metadata.name of the Zhihu archive snapshot.")
                                        .in(ParameterIn.PATH)
                                        .required(true)
                                        .implementation(String.class))
                                .response(responseBuilder().responseCode("204").description("Archive deleted")))
                .POST(
                        "zhihu-archives/{name}/transfer-video",
                        this::transferVideo,
                        builder -> builder.operationId("TransferZhihuArchiveVideo")
                                .description("Transfer the source video of a snapshot into attachment storage. "
                                        + "Repeated calls return the existing local reference.")
                                .tag(tag)
                                .parameter(parameterBuilder()
                                        .name("name")
                                        .description("metadata.name of the Zhihu archive snapshot.")
                                        .in(ParameterIn.PATH)
                                        .required(true)
                                        .implementation(String.class))
                                .response(responseBuilder().implementation(SinglePage.class)))
                .POST(
                        "zhihu-archives/index/rebuild",
                        this::rebuildIndex,
                        builder -> builder.operationId("RebuildZhihuArchiveIndex")
                                .description("Regenerate the archive index page listing all snapshots newest first.")
                                .tag(tag)
                                .response(responseBuilder().implementation(SinglePage.class)))
                .build();
    }

    private Mono<ServerResponse> listZhihuArchives(ServerRequest request) {
        return Mono.defer(() -> {
            var page = parsePageParam(request, "page", DEFAULT_PAGE, Integer.MAX_VALUE);
            var size = parsePageParam(request, "size", DEFAULT_SIZE, MAX_SIZE);
            return zhihuArchiveService.list().collectList().flatMap(items -> {
                var total = items.size();
                var pageItems = ListResult.subList(items, page, size);
                return ok(new ListResult<>(page, size, total, pageItems));
            });
        });
    }

    private Mono<ServerResponse> createZhihuArchive(ServerRequest request) {
        return request.bodyToMono(ZhihuArchiveCreateRequest.class)
                .switchIfEmpty(Mono.error(() -> new ServerWebInputException("Request body is required.")))
                .flatMap(createRequest -> {
                    var violations = validator.validate(createRequest);
                    if (!violations.isEmpty()) {
                        return Mono.error(new ServerWebInputException("Invalid request: " + violations));
                    }
                    return currentOwner(request).flatMap(owner -> zhihuArchiveService.create(createRequest, owner));
                })
                .onErrorMap(IllegalArgumentException.class, ZhihuArchiveEndpoint::badRequest)
                .flatMap(ZhihuArchiveEndpoint::ok);
    }

    private Mono<ServerResponse> deleteZhihuArchive(ServerRequest request) {
        return zhihuArchiveService
                .delete(request.pathVariable("name"))
                .onErrorMap(IllegalArgumentException.class, ZhihuArchiveEndpoint::badRequest)
                .then(ServerResponse.noContent().build());
    }

    private Mono<ServerResponse> transferVideo(ServerRequest request) {
        return zhihuArchiveService
                .transferVideo(request.pathVariable("name"))
                .onErrorMap(IllegalStateException.class, ZhihuArchiveEndpoint::badRequest)
                .flatMap(ZhihuArchiveEndpoint::ok);
    }

    private Mono<ServerResponse> rebuildIndex(ServerRequest request) {
        return currentOwner(request)
                .flatMap(zhihuArchiveService::rebuildIndex)
                .onErrorMap(IllegalStateException.class, ZhihuArchiveEndpoint::badRequest)
                .flatMap(ZhihuArchiveEndpoint::ok);
    }

    private static ServerWebInputException badRequest(RuntimeException error) {
        return new ServerWebInputException(error.getMessage());
    }

    private static Mono<String> currentOwner(ServerRequest request) {
        return request.principal().map(Principal::getName).defaultIfEmpty("anonymous");
    }

    private static Mono<ServerResponse> ok(Object body) {
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body);
    }

    private static int parsePageParam(ServerRequest request, String name, int defaultValue, int maxValue) {
        var rawValue = request.queryParam(name).orElse(null);
        if (rawValue == null || rawValue.isBlank()) {
            return defaultValue;
        }
        final int value;
        try {
            value = Integer.parseInt(rawValue.trim());
        } catch (NumberFormatException e) {
            throw new ServerWebInputException("Invalid query parameter [" + name + "]: " + rawValue);
        }
        if (value < 1 || value > maxValue) {
            throw new ServerWebInputException(
                    "Query parameter [" + name + "] must be between 1 and " + maxValue + ": " + rawValue);
        }
        return value;
    }
}
