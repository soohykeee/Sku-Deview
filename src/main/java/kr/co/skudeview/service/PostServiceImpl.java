package kr.co.skudeview.service;

import kr.co.skudeview.domain.Member;
import kr.co.skudeview.domain.Post;
import kr.co.skudeview.domain.PostFile;
import kr.co.skudeview.domain.enums.PostCategory;
import kr.co.skudeview.infra.exception.NotFoundException;
import kr.co.skudeview.infra.model.ResponseStatus;
import kr.co.skudeview.repository.MemberRepository;
import kr.co.skudeview.repository.PostFileRepository;
import kr.co.skudeview.repository.PostRepository;
import kr.co.skudeview.repository.search.PostSearchRepository;
import kr.co.skudeview.service.dto.request.PostRequestDto;
import kr.co.skudeview.service.dto.response.PostResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;
import java.io.File;
import java.io.IOException;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PostServiceImpl implements PostService {

    private final PostRepository postRepository;

    private final MemberRepository memberRepository;

    private final PostSearchRepository postSearchRepository;
    private final PostFileRepository postFileRepository;

    private final RedisTemplate<String, Object> redisTemplate; // RedisTemplate 주입


    @Override
    @Transactional
    public Long createPost(String email, PostRequestDto.CREATE create) throws IOException {
        Optional<Member> findMember = memberRepository.findMemberByEmailAndDeleteAtFalse(email);


        isMember(findMember);

        isPostCategory(String.valueOf(create.getPostCategory()));

        if (create.getPostFile().isEmpty()) {
            //첨부 파일 없음
            Post post = toCreateEntity(create, findMember.get());
            postRepository.save(post);
            return post.getId();
        } else {
            //첨부 파일 있음
            MultipartFile postFile = create.getPostFile();
            String originalFilename = postFile.getOriginalFilename();
            String storedFileName = System.currentTimeMillis() + "_" + originalFilename;
            String savePath = "C:/deview_img/" + storedFileName;
            postFile.transferTo(new File(savePath)); //IO Exception
            Post postEntity = toCreateFileEntity(create, findMember.get());
            Long savedId = postRepository.save(postEntity).getId();
            Post post = postRepository.findById(savedId).get();

            PostFile postFileEntity = toPostFileEntity(originalFilename, storedFileName, post);
            postFileRepository.save(postFileEntity);
            return post.getId();
        }

    }

    @Override
    public List<PostResponseDto.READ> getAllPosts() {
        List<Post> list = postRepository.findAllByDeleteAtFalse(); //추후 삭제 여부에 따른 동적쿼리로 변경
        List<PostResponseDto.READ> posts = new ArrayList<>();

        for (Post post : list) {
            PostResponseDto.READ dto = toDto(post);

            posts.add(dto);
        }
        return posts;
    }

    @Override
    @Transactional
    public Long updatePost(String email, Long postId, PostRequestDto.UPDATE update) {
        //email을 통한 인가 추가 예정(프론트에서 진행해야 할 것으로 생각 중 논의 예정)

        Optional<Post> post = postRepository.findPostByIdAndDeleteAtFalse(postId);

        isPost(post);

        post.get().updatePost(update.getTitle(), update.getContent(), update.getPostCategory());

        return postId;
    }

    @Override
    @Transactional
    public Long deletePost(Long postId) {
        Optional<Post> post = postRepository.findPostByIdAndDeleteAtFalse(postId);

        isPost(post);

        post.get().changeDeleteAt();

        postRepository.save(post.get());

        return post.get().getId();
    }

    /**
     * 게시글 상세정보 조회
     */
    @Override
    @Transactional
    public PostResponseDto.READ getPostDetail(Long postId) {
        Optional<Post> post = postRepository.findPostByIdAndDeleteAtFalse(postId);

        isPost(post);

        updateCntToRedis(postId, "views");

        return toDto(post.get());
    }

    @Override
    public List<PostResponseDto.READ> getSearchPosts(PostRequestDto.CONDITION condition) {
        final List<Post> posts = postSearchRepository.find(condition);

        return posts.stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Override
    public Page<PostResponseDto.READ> searchPostWithPaging(PostRequestDto.CONDITION condition, Pageable pageable) {
        return postSearchRepository.findWithPaging(condition, pageable).map(this::toDto);
    }

    /*
    게시글 상세조회 요청 시, 해당 postId에 해당하는 viewCnt를 +1 해준 값을 Redis에 저장
     */
    @Transactional
    @Override
    public void updateCntToRedis(Long postId, String hashKey) {
        HashOperations<String, String, Object> hashOperations = redisTemplate.opsForHash();

        String key = "postId::" + postId;

        if (hashOperations.get(key, hashKey) == null) {
            if (hashKey.equals("views")) {
                hashOperations.put(key, hashKey, postRepository.findPostByIdAndDeleteAtFalse(postId).get().getViewCount());
            } else {
                hashOperations.put(key, hashKey, postRepository.findPostByIdAndDeleteAtFalse(postId).get().getLikeCount());
            }
            hashOperations.increment(key, hashKey, 1L);
            System.out.println("hashOperations.get is null ---- " + hashOperations.get(key, hashKey));
        } else {
            hashOperations.increment(key, hashKey, 1L);
            System.out.println("hashOperations.get is not null ---- " + hashOperations.get(key, hashKey));
        }
    }

    /*
    Redis에 기록된 정보들을 DB에 업데이트를 진행하면서 데이터의 일관성을 유지하고, Redis의 저장된 정보들을 초기화
    Spring Scheduled를 사용하여 일정 시간마다 실행이 되도록 설정
     */
    @Transactional
    @Scheduled(fixedDelay = 1000L * 18L)
    @Override
    public void deleteCntToRedis() {
        String viewKey = "views";
        String likeKey = "likes";
        Set<String> redisKey = redisTemplate.keys("postId*");
        Iterator<String> it = redisKey.iterator();

        while (it.hasNext()) {
            String data = it.next();
            Long postId = Long.parseLong(data.split("::")[1]);

            if (redisTemplate.opsForHash().get(data, viewKey) == null) {
                break;
            } else {
                Long viewCnt = Long.parseLong(String.valueOf(redisTemplate.opsForHash().get(data, viewKey)));
                addViewCntFromRedis(postId, viewCnt);
                redisTemplate.opsForHash().delete(data, viewKey);
            }

            if (redisTemplate.opsForHash().get(data, likeKey) == null) {
                break;
            } else {
                Long likeCnt = Long.parseLong(String.valueOf(redisTemplate.opsForHash().get(data, likeKey)));
                addLikeCntFromRedis(postId, likeCnt);
                redisTemplate.opsForHash().delete(data, likeKey);
            }
        }
        System.out.println("Update Complete From Redis");
    }

    private void addViewCntFromRedis(Long postId, Long viewCnt) {
        Optional<Post> post = postRepository.findPostByIdAndDeleteAtFalse(postId);

        isPost(post);

//        post.get().addViewCount(post.get().getViewCount() + viewCnt);
        post.get().addViewCount(viewCnt);

        postRepository.save(post.get());
    }

    private void addLikeCntFromRedis(Long postId, Long likeCnt) {
        Optional<Post> post = postRepository.findPostByIdAndDeleteAtFalse(postId);

        isPost(post);

//        post.get().addLikeCount(post.get().getLikeCount() + likeCnt);
        post.get().addLikeCount(likeCnt);

        postRepository.save(post.get());
    }

    private void isMember(Optional<Member> member) {
        if (member.isEmpty()) {
            throw new NotFoundException(ResponseStatus.FAIL_MEMBER_NOT_FOUND);
        }
    }

    private void isPost(Optional<Post> post) {
        if (post.isEmpty()) {
            throw new NotFoundException(ResponseStatus.FAIL_POST_NOT_FOUND);
        }
    }

    private void isPostCategory(String category) {
        PostCategory.of(category);
    }

    private PostResponseDto.READ toDto(Post post) {
        if (post.getFileAttached() == 0) {
            return PostResponseDto.READ.builder()
                    .postId(post.getId())
                    .memberEmail(post.getMember().getEmail())
                    .title(post.getTitle())
                    .content(post.getContent())
                    .postCategory(post.getPostCategory())
                    .viewCount(post.getViewCount())
                    .likeCount(post.getLikeCount())
                    .fileAttached(post.getFileAttached())
                    .build();

        } else {
            return PostResponseDto.READ.builder()
                    .postId(post.getId())
                    .memberEmail(post.getMember().getEmail())
                    .title(post.getTitle())
                    .content(post.getContent())
                    .postCategory(post.getPostCategory())
                    .viewCount(post.getViewCount())
                    .likeCount(post.getLikeCount())
                    .fileAttached(post.getFileAttached())
                    .originalFileName(post.getPostFileList().get(0).getOriginalFileName())
                    .storedFileName(post.getPostFileList().get(0).getStoredFileName())
                    .build();
        }

    }

//    private static Post toEntity(PostRequestDto.CREATE create, Member member) {
//        Post post = Post.builder()
//                .member(member)
//                .title(create.getTitle())
//                .content(create.getContent())
//                .likeCount(create.getLikeCount())
//                .viewCount(create.getViewCount())
//                .postCategory(create.getPostCategory())
//                .build();
//        return post;
//    }

    private static Post toCreateEntity(PostRequestDto.CREATE create, Member member) {
        Post post = Post.builder()
                .member(member)
                .title(create.getTitle())
                .content(create.getContent())
                .likeCount(create.getLikeCount())
                .viewCount(create.getViewCount())
                .postCategory(create.getPostCategory())
                .fileAttached(0)
                .build();
        return post;

    }

    private static Post toCreateFileEntity(PostRequestDto.CREATE create, Member member) {
        Post post = Post.builder()
                .member(member)
                .title(create.getTitle())
                .content(create.getContent())
                .likeCount(create.getLikeCount())
                .viewCount(create.getViewCount())
                .postCategory(create.getPostCategory())
                .fileAttached(1)
                .build();
        return post;

    }

    private static PostFile toPostFileEntity(String originalFileName, String storedFileName, Post post) {
        return PostFile.builder()
                .originalFileName(originalFileName)
                .storedFileName(storedFileName)
                .post(post)
                .build();
    }
}
