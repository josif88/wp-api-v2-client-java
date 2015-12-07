package com.afrozaar.wordpress.wpapi.v2;

import com.afrozaar.wordpress.wpapi.v2.exception.PostCreateException;
import com.afrozaar.wordpress.wpapi.v2.model.Link;
import com.afrozaar.wordpress.wpapi.v2.model.Post;
import com.afrozaar.wordpress.wpapi.v2.model.PostMeta;
import com.afrozaar.wordpress.wpapi.v2.request.Request;
import com.afrozaar.wordpress.wpapi.v2.request.SearchRequest;
import com.afrozaar.wordpress.wpapi.v2.request.UpdatePostRequest;
import com.afrozaar.wordpress.wpapi.v2.response.PagedResponse;
import com.afrozaar.wordpress.wpapi.v2.util.AuthUtil;
import com.afrozaar.wordpress.wpapi.v2.util.Two;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class Client implements Wordpress {
    private static final Logger LOG = LoggerFactory.getLogger(Client.class);

    private RestTemplate restTemplate = new RestTemplate(Arrays.asList(new MappingJackson2HttpMessageConverter()));
    private final Predicate<Link> next = link -> Strings.NEXT.equals(link.getRel());
    private final Predicate<Link> previous = link -> Strings.PREV.equals(link.getRel());

    public final String baseUrl;
    final private String username;
    final private String password;
    final private boolean debug;

    final ImmutableMap<String, List<String>> EMPTY_MAP = ImmutableMap.of();

    public Client(String baseUrl, String username, String password, boolean debug) {
        this.baseUrl = baseUrl;
        this.username = username;
        this.password = password;
        this.debug = debug;
    }

    @Override
    public Post createPost(Map<String, Object> post) throws PostCreateException {
        try {
            final URI uri = Request.of(Request.POSTS).usingClient(this).build().toUri();
            return doExchange0(HttpMethod.POST, uri, Post.class, post).getBody();
        } catch (HttpClientErrorException e) {
            throw new PostCreateException(e);
        }
    }

    @Override
    public Post createPost(Post post) throws PostCreateException {
        return createPost(fieldsFrom(post));
    }

    private Map<String, Object> fieldsFrom(Post post) {
        ImmutableMap.Builder<String, Object> builder = new ImmutableMap.Builder<>();

        populateEntry(post::getDate, builder, "date");
        populateEntry(post::getModifiedGmt, builder, "modified_gmt");
        populateEntry(post::getSlug, builder, "slug");
        //populateEntry(post::getCommentStatus, builder, "status");
        populateEntry(() -> post.getTitle().getRendered(), builder, "title");
        populateEntry(() -> post.getContent().getRendered(), builder, "content");
        populateEntry(post::getAuthor, builder, "author");
        populateEntry(() -> post.getExcerpt().getRendered(), builder, "excerpt");
        populateEntry(post::getCommentStatus, builder, "comment_status");
        populateEntry(post::getPingStatus, builder, "ping_status");
        populateEntry(post::getFormat, builder, "format");
        populateEntry(post::getSticky, builder, "sticky");

        return builder.build();
    }

    <T> void populateEntry(Supplier<T> supplier, ImmutableMap.Builder<String, Object> builder, String key) {
        Optional.ofNullable(supplier.get()).ifPresent(value -> builder.put(key, value));
    }



    @Override
    public Post getPost(Integer id) {
        final URI uri = Request.of(Request.POST).usingClient(this).buildAndExpand(id).toUri();
        final ResponseEntity<Post> exchange = doExchange(HttpMethod.GET, uri, Post.class, null);

        return exchange.getBody();
    }

    @Override
    public Post updatePost(Post post) {
        final URI uri = UpdatePostRequest.forPost(post).usingClient(this).buildAndExpand(post.getId()).toUri();
        final ResponseEntity<Post> exchange = doExchange(HttpMethod.PUT, uri, Post.class, post);

        return exchange.getBody();
    }

    @Override
    public Post deletePost(Post post) {
        final UriComponents uriComponents = Request.of(Request.POST).usingClient(this).buildAndExpand(post.getId());
        final ResponseEntity<Post> exchange = doExchange0(HttpMethod.DELETE, uriComponents, Post.class, null); // Deletion of a post returns the post's data before removing it.
        Preconditions.checkArgument(exchange.getStatusCode().is2xxSuccessful());
        return exchange.getBody();
    }

    @Override
    public PagedResponse<Post> fetchPosts(SearchRequest<Post> search) {
        final URI uri = search.forHost(baseUrl, CONTEXT).build().toUri();
        final ResponseEntity<Post[]> exchange = doExchange(HttpMethod.GET, uri, Post[].class, null);

        final HttpHeaders headers = exchange.getHeaders();
        final List<Link> links = parseLinks(headers);
        final List<Post> posts = Arrays.asList(exchange.getBody());

        LOG.trace("{} returned {} posts.", uri, posts.size());

        return PagedResponse.Builder.<Post>aPagedResponse()
                .withPages(headers)
                .withPosts(posts)
                .withSelf(uri.toASCIIString())
                .withNext(link(links, next))
                .withPrevious(link(links, previous))
                .build();
    }


    @Override
    public PostMeta createMeta(Integer postId, String key, String value) {
        final URI uri = Request.of(Request.METAS).usingClient(this).buildAndExpand(postId).toUri();
        final ResponseEntity<PostMeta> exchange = doExchange0(HttpMethod.POST, uri, PostMeta.class, ImmutableMap.of("key", key, "value", value));
        return exchange.getBody();
    }

    @Override
    public List<PostMeta> getPostMetas(Integer postId) {
        final URI uri = Request.of(Request.METAS).usingClient(this).buildAndExpand(postId).toUri();
        final ResponseEntity<PostMeta[]> exchange = doExchange(HttpMethod.GET, uri, PostMeta[].class, null);
        return Arrays.asList(exchange.getBody());
    }

    @Override
    public PostMeta getPostMeta(Integer postId, Integer metaId) {
        final URI uri = Request.of(Request.META).usingClient(this).buildAndExpand(postId, metaId).toUri();
        final ResponseEntity<PostMeta> exchange = doExchange(HttpMethod.GET, uri, PostMeta.class, null);
        return exchange.getBody();
    }

    @Override
    public PostMeta updatePostMetaValue(Integer postId, Integer metaId, String value) {
        return updatePostMeta(postId, metaId, null, value);
    }

    @Override
    public PostMeta updatePostMeta(Integer postId, Integer metaId, String key, String value) {
        final URI uri = Request.of(Request.META).usingClient(this).buildAndExpand(postId, metaId).toUri();

        ImmutableMap.Builder<String, Object> builder = new ImmutableMap.Builder<>();
        populateEntry(() -> key, builder, "key");
        populateEntry(() -> value, builder, "value");

        final ImmutableMap<String, Object> body = builder.build();
        final ResponseEntity<PostMeta> exchange = doExchange0(HttpMethod.POST, uri, PostMeta.class, body);

        return exchange.getBody();
    }

    @Override
    public boolean deletePostMeta(Integer postId, Integer metaId) {
        final UriComponents uriComponents = Request.of(Request.META).usingClient(this).buildAndExpand(postId, metaId);

        final ResponseEntity<Map> exchange = doExchange0(HttpMethod.DELETE, uriComponents, Map.class, null);

        Preconditions.checkArgument(exchange.getStatusCode().is2xxSuccessful(), String.format("Expected success on post meta delete request: /posts/%s/meta/%s", postId, metaId));

        return exchange.getStatusCode().is2xxSuccessful();
    }

    @Override
    public boolean deletePostMeta(Integer postId, Integer metaId, boolean force) {
        final UriComponents uriComponents = Request.of(Request.META).usingClient(this)
                .queryParam("force", force)
                .buildAndExpand(postId, metaId);

        final ResponseEntity<Map> exchange = doExchange0(HttpMethod.DELETE, uriComponents, Map.class, null);

        Preconditions.checkArgument(exchange.getStatusCode().is2xxSuccessful(), String.format("Expected success on post meta delete request: /posts/%s/meta/%s", postId, metaId));

        return exchange.getStatusCode().is2xxSuccessful();
    }

    private <T> ResponseEntity<T> doExchange(HttpMethod method, URI uri, Class<T> typeRef, T body) {
        return doExchange0(method, uri, typeRef, body);
    }

    private <T,B> ResponseEntity<T> doExchange0(HttpMethod method, URI uri, Class<T> typeRef, B body) {
        final Two<String, String> authTuple = AuthUtil.authTuple(username, password);
        final RequestEntity<B> entity = RequestEntity.method(method, uri).header(authTuple.k, authTuple.v).body(body);
        debugRequest(entity);
        final ResponseEntity<T> exchange = restTemplate.exchange(entity, typeRef);
        debugHeaders(exchange.getHeaders());
        return exchange;
    }
    private <T,B> ResponseEntity<T> doExchange0(HttpMethod method, UriComponents uriComponents, Class<T> typeRef, B body) {
        return doExchange0(method, uriComponents.toUri(), typeRef, body);
    }

    private Optional<String> link(List<Link> links, Predicate<? super Link> linkPredicate) {
        return links.stream()
                .filter(linkPredicate)
                .map(Link::getHref)
                .findFirst();
    }

    private void debugRequest(RequestEntity<?> entity) {
        if (debug) {
            LOG.debug("Request Entity: {}", entity);
        }
    }

    private void debugHeaders(HttpHeaders headers) {
        if (debug) {
            LOG.debug("Response Headers:");
            headers.entrySet().stream().forEach(entry -> LOG.debug("{} -> {}", entry.getKey(), entry.getValue()));
        }
    }

    public List<Link> parseLinks(HttpHeaders headers) {
        //Link -> [<http://johan-wp/wp-json/wp/v2/posts?page=2>; rel="next"]

        Optional<List<String>> linkHeader = Optional.ofNullable(headers.get(Strings.HEADER_LINK));
        if (linkHeader.isPresent()) {
            final String rawResponse = linkHeader.get().get(0);
            final String[] links = rawResponse.split(", ");

            return Arrays.stream(links).map(link -> { // <http://johan-wp/wp-json/wp/v2/posts?page=2>; rel="next"
                String[] linkData = link.split("; ");
                final String href = linkData[0].replace("<", "").replace(">", "");
                final String rel = linkData[1].substring(4).replace("\"", "");
                return Link.of(href, rel);
            }).collect(Collectors.toList());
        } else {
            return Collections.emptyList();
        }

    }

    @Override
    public PagedResponse<Post> get(PagedResponse<Post> postPagedResponse, Function<PagedResponse<Post>, String> previousOrNext) {
        return fetchPosts(fromPagedResponse(postPagedResponse, previousOrNext));
    }

    @Override
    public SearchRequest<Post> fromPagedResponse(PagedResponse<Post> response, Function<PagedResponse<Post>, String> previousOrNext) {
        return Request.fromLink(previousOrNext.apply(response), CONTEXT);
    }
}
