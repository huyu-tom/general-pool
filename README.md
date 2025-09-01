目的:  实现通用的池功能

思想来源: [HikariCP](https://github.com/brettwooldridge/HikariCP),更改了一些底层数据结构,使其能适合更多池情况

1. 支持池中内容判断借出,需要满足条件才进行借出(验证码池(尽量同一个token尽量短时间不解出))。
2. 支持虚拟线程的适配    
   > - 虚拟线程不采用ThreadLocal,本采用jdk.internal.misc.CarrierThreadLocal,但是实际使用,如果虚拟线程会采用线程安全问题(
   get的值会被多个虚拟线程共享,会存储虚拟线程的栈帧中,所以会有线程安全问题)  
   > - 虚拟线程的时候采用一个公共池,但是实际效果不是很如意,线程上下文切换,

基准测试结果:

```text
Benchmark            (mindle)  (poolSize)   Mode  Cnt        Score   Error  Units
PoolJMH.commonsPool      1000       50000  thrpt    2  2589886.897          ops/s
PoolJMH.generalPool      1000       50000  thrpt    2  2804709.457          ops/s
```

> 总结:   
> commonsPool是apache的commons-pool2的实现,  
> generalPool是本库的实现
