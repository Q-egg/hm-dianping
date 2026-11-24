package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.PasswordEncoder;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
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
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> CONSUME_CODE_SCRIPT =
            new DefaultRedisScript<>(
                    "if redis.call('GET', KEYS[1]) == ARGV[1] then "
                            + "return redis.call('DEL', KEYS[1]) end return 0",
                    Long.class);

    /**
     * 发送短信验证码
     */
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            //2.如果手机号错误
            return Result.fail("手机号格式错误");
        }
        //3.生成验证码
        String code = RandomUtil.randomNumbers(6);

        //4.保存验证码到redis // set key value ex 120
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone,code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        //5.发送验证码
        log.info("发送验证码成功，验证码为：{}",code);

        return Result.ok();
        
    }

    /**
     * 登录功能
     */
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            //2.如果手机号错误
            return Result.fail("手机号格式错误");
        }

        String code = loginForm.getCode();
        String password = loginForm.getPassword();
        boolean hasCode = StrUtil.isNotBlank(code);
        boolean hasPassword = StrUtil.isNotBlank(password);
        if (hasCode == hasPassword) {
            return Result.fail("请提供短信验证码或密码，二选一");
        }

        if (hasCode) {
            String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
            if (!code.equals(cacheCode)) {
                return Result.fail("验证码错误");
            }
        }

        User user = query().eq("phone", phone).one();
        if (hasPassword) {
            if (user == null || !PasswordEncoder.matches(user.getPassword(), password)) {
                return Result.fail("手机号或密码错误");
            }
        } else if (user == null) {
            user = createUserWithPhone(phone);
        }
        //7.将用户信息保存到redis
        //7.1随机生成token
        String token = UUID.randomUUID().toString(true);

        //7.2将user转换成hashMap存储
        UserDTO userDTO = new UserDTO();
        BeanUtil.copyProperties(user, userDTO);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions
                        .create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue == null ? null : fieldValue.toString()));
        //7.3存入redis
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        //7.4设置有效期
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);
        //8.将token返回给前端
        return Result.ok(token);
    }

    @Override
    public Result sign() {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();

        //2.获取当前日期
        LocalDateTime now = LocalDateTime.now();

        //3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;

        //4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();

        //5.写入redis setbit key offset 1
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth - 1,true);

        return Result.ok();
    }

    @Override
    public Result logout(String token) {
        if (StrUtil.isBlank(token)) {
            return Result.ok();
        }
        //将当前用户从redis中清除
        String key = LOGIN_USER_KEY + token;
        stringRedisTemplate.delete(key);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();

        //2.获取当前日期
        LocalDateTime now = LocalDateTime.now();

        //3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;

        //4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();

        //5.获取本月截止到今天为止的所有签到记录
        List<Long> result = stringRedisTemplate.opsForValue().bitField(key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType
                                .unsigned(dayOfMonth)).valueAt(0));
        if(result == null || result.isEmpty()){
            return Result.ok(0);
        }
        Long num = result.get(0);
        if(num == null || num == 0){
            return Result.ok(0);
        }
        int count = 0;
        //6.循环遍历
        while(true){
            //7.让这个数字与1做与运算，得到最后一个bit位 bitfield key get u14 0
            if ((num & 1) == 0) {
                break;
            }else {
                count++;
            }
            num >>>= 1;
        }


        return Result.ok(count);
    }

    @Override
    public Result setPassword(LoginFormDTO loginFormDTO) {
        //1.获取登录密码
        String password = loginFormDTO.getPassword();
        if(StrUtil.isBlank(password))
        {
            return Result.fail("密码不能为空");
        }
        if(password.length() < 6 || password.length() > 20)
        {
            return Result.fail("密码不能太短或太长");
        }
        Long userId = UserHolder.getUser().getId();

        //2.将密码进行加密
        String passwordHash = PasswordEncoder.encode(password);
        //UPDATE tb_user
        //SET password = ?
        //WHERE id = ?
        // AND (password IS NULL OR password = '');  判断是否有密码了
        boolean updated = update()
                .set("password", passwordHash)
                .eq("id", userId)
                .and(w -> w.isNull("password").or().eq("password", ""))
                .update();

        return updated ? Result.ok() : Result.fail("该账号已设置密码或不存在");
    }

    @Override
    public Result resetPassword(LoginFormDTO loginFormDTO) {
        //1.校验手机号
        String phone = loginFormDTO.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            //2.如果手机号错误
            return Result.fail("手机号格式错误");
        }

        //2.校验新密码
        String password = loginFormDTO.getPassword();
        if(StrUtil.isBlank(password))
        {
            return Result.fail("密码不能为空");
        }
        if(password.length() < 6 || password.length() > 20)
        {
            return Result.fail("密码不能太短或太长");
        }

        //3.校验验证码
        String code = loginFormDTO.getCode();
        if (code == null || !code.matches("[0-9]{6}")) {
            return Result.fail("请求参数不合法");
        }

        Long consumed = stringRedisTemplate.execute(
                CONSUME_CODE_SCRIPT, Collections.singletonList(LOGIN_CODE_KEY + phone), code);
        if (!Long.valueOf(1L).equals(consumed)) {
            return Result.fail("手机号或验证码无效");
        }

        //4.重置密码
        String passwordHash = PasswordEncoder.encode(password);
        boolean updated = update()
                .set("password", passwordHash)
                .eq("phone", phone)
                .update();
        if(!updated)
        {
            return Result.fail("手机号或验证码无效");
        }
        return Result.ok();
    }


    //创建用户
    private User createUserWithPhone(String phone) {
        //1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        //2.保存用户
        save(user);
        return user;

    }
}
