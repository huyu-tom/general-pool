目的:  实现通用的池功能

思想来源: [HikariCP](https://github.com/brettwooldridge/HikariCP),更改了一些底层数据结构,使其能适合更多池情况

1. 支持池中内容判断借出,需要满足条件才进行借出(验证码池(尽量同一个token尽量短时间不解出))
2. 支持虚拟线程的适配 jdk.internal.misc.CarrierThreadLocal
