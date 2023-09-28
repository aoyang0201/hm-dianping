package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;
    @Override
    public Result follow(Long followUserId, Boolean isfollow) {
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        if (isfollow){
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            if(isSuccess){
                stringRedisTemplate.opsForSet().add(key,followUserId.toString());
            }

        }
        else {
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId)
                    .eq("follow_user_id", followUserId));
            if(isSuccess){
                stringRedisTemplate.opsForSet().remove(key,followUserId.toString());
            }
        }
        return Result.ok();
    }
    @Override
    public Result isfollow(Long followUserId) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.查询是否关注 select count(*) from tb_follow where user_id = ? and follow_user_id = ?
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        // 3.判断
        return Result.ok(count > 0);
    }

    @Override
    public Result followCommens(Long Id) {
        Long userid = UserHolder.getUser().getId();
        String key1 = "follows:" + userid;
        String key2 = "follows:" + Id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if (intersect == null || intersect.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> userDTOS = userService.listByIds(ids).stream().map(user ->
                BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
/*      当交集非空时，我们需要将交集中的用户ID进行处理和转换。
        intersect.stream().map(Long::valueOf)：
        这一步将交集集合 intersect 中的每个元素（用户ID）进行转换。
        通过 map 方法，我们使用Long::valueOf 函数将每个元素（用户ID）转换为Long类型。
        这样，我们得到了一个新的 Stream<Long> 类型的流对象。
        collect(Collectors.toList())：
        这一步将转换后的流对象收集到一个新的列表中。
        通过 collect 方法，我们使用 Collectors.toList() 指定将流中的元素收集到一个列表中。
        接下来，我们需要根据这些用户ID查询对应的用户信息，并将其转换为 UserDTO 对象列表。
        userService.listByIds(ids)：
        这一步通过用户ID列表 ids 调用 userService 的 listByIds 方法，
        查询对应的用户信息。这将返回一个包含用户信息的列表。
        .stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList())：
        这一步将查询到的用户信息列表中的每个用户对象转换为 UserDTO 对象，
        并将其收集到一个新的列表中。通过 stream 方法将列表转换为流对象，
        然后使用 map 方法将每个用户对象转换为 UserDTO 对象。
        在这里我们使用了 BeanUtil.copyProperties 方法来进行对象属性的复制。
        最后，通过 collect 方法将转换后的流对象收集到一个新的列表中。
        最终，我们得到了一个包含转换后的 UserDTO 对象的列表，即 userDTOS。*/
        return Result.ok(userDTOS);
    }
}
