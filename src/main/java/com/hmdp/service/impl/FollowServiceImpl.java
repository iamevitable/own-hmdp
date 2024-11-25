package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.utils.UserHolder;
import org.apache.ibatis.session.SqlSession;
import org.springframework.stereotype.Service;

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

    @Override
    public Result follow(Long followUserID, Boolean isFollowed) {
        Long userId = UserHolder.getUser().getId();
        //1. 判断是关注还是取关
        if(BooleanUtil.isTrue(isFollowed)){
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserID);
            save(follow);
        }else {
            remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId).eq("follow_user_id", followUserID));
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserID) {
        Long userId = UserHolder.getUser().getId();
        Long count = query().eq("user_id", userId).eq("follow_user_id", followUserID).count();
        return Result.ok(count > 0);
    }
}
