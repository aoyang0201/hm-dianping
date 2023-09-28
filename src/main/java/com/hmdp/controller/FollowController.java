package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {
    @Resource
    IFollowService followService;

    @PutMapping("/{id}/{follow}")
    public Result follow(@PathVariable("id") Long followUserId,@PathVariable("follow")Boolean isfollow){
        return followService.follow(followUserId,isfollow);
    }
    @GetMapping("or/not/{id}")
    public Result follow(@PathVariable("id") Long followUserId){
        return followService.isfollow(followUserId);
    }

    @GetMapping("common/{id}")
    public Result followCommens(@PathVariable("id") Long Id){
        return followService.followCommens(Id);
    }
}
