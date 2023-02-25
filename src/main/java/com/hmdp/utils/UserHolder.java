package com.hmdp.utils;

import com.hmdp.dto.UserDTO;

/**
 * ThreadLocal 用户的线程域对象
 *
 * @author Mr.Ye
 */
public class UserHolder {
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();

    public static void saveUser(UserDTO user){
        tl.set(user);
    }

    public static UserDTO getUser(){
        return tl.get();
    }

    public static void removeUser(){
        tl.remove();
    }

    private UserHolder(){}
}
