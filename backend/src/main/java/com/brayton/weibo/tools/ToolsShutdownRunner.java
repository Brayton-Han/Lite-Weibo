package com.brayton.weibo.tools;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * tools profile 下，在所有工具型 CommandLineRunner 跑完之后关闭应用。
 *
 * <h3>为什么不能在导入器内部调 System.exit(0)</h3>
 * {@code CorpusImportRunner#run} 标了 {@code @Transactional}，事务在该方法**返回时**
 * 才由代理提交。在方法内部调 System.exit 会在提交前杀掉 JVM，3000 条数据全部丢失。
 * {@code FeedBackfillRunner} 可以那么写，是因为它是 {@code readOnly} 事务，没有要提交的东西。
 *
 * 本类以 {@link Ordered#LOWEST_PRECEDENCE} 排在最后执行，此时前面每个 Runner 的
 * 事务都已越过代理边界完成提交。
 *
 * ⚠️ 新增工具型 Runner 时<b>必须显式标 {@code @Order}</b> 且优先级高于本类。
 * 未标注的 Bean 默认也是 LOWEST_PRECEDENCE，与本类同级会让执行顺序退化为
 * 不确定的 Bean 定义顺序——实测出现过本类先跑、工具根本没执行、日志却看不出异常的情况。
 */
@Component
@Profile("tools")
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class ToolsShutdownRunner implements CommandLineRunner {

    private final ConfigurableApplicationContext context;

    @Override
    public void run(String... args) {
        System.out.println("🔚 tools profile：工具执行完毕，关闭应用。");
        System.exit(SpringApplication.exit(context, () -> 0));
    }
}
