package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1. 校验手机号码是否合法
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号码不合法");
        }
        //2. 生成验证码
        String code = RandomUtil.randomNumbers(6);
        //3. 保存验证码到redis 时效两分钟
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone , code, LOGIN_CODE_TTL , TimeUnit.MINUTES);

        //4. 发送验证码到手机
        log.debug("验证码：" + code);
        //TODO 发送验证码到手机
        //5. 返回结果
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm) {
        //1. 校验手机号码
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号码不合法");
        }
        //2. 校验验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code=loginForm.getCode();
        if(cacheCode == null || !cacheCode.equals(code)){
            return Result.fail("验证码错误");
        }
        //4. 一致 根据手机号查找用户
        User user = query().eq("phone", phone).one();

        //5. 判断用户是否存在
        if(user == null){
            //6. 不存在，创建新用户 并存入数据库
            user = createUserWithPhone(phone);
        }
        //7. 生成token 存入redis
        String token = UUID.randomUUID().toString(true);
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true).setFieldValueEditor((fieldName,fieldValue)->
                    fieldValue.toString()
                ));
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY+token,userMap);
        stringRedisTemplate.expire(LOGIN_USER_KEY+token,LOGIN_USER_TTL,TimeUnit.SECONDS);
        return Result.ok(token);
    }
    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomNumbers(10));
        save(user);
        return user;
    }
}
