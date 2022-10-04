package org.apache.nifi.marklogic.processor;

import com.marklogic.client.DatabaseClient;
import com.marklogic.client.expression.PlanBuilder;
import com.marklogic.client.io.InputStreamHandle;
import com.marklogic.client.io.StringHandle;
import com.marklogic.client.row.RowManager;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.Validator;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.*;
import org.apache.nifi.processor.exception.ProcessException;
import org.springframework.util.FileCopyUtils;

import java.io.InputStream;
import java.util.*;

@Tags({"MarkLogic", "Query", "Read", "Rows"})
@InputRequirement(InputRequirement.Requirement.INPUT_ALLOWED)
@CapabilityDescription("Executes a serialized Optic query plan and writes the returned rows to a FlowFile")
public class QueryRowsMarkLogic extends AbstractMarkLogicProcessor {

	public static final PropertyDescriptor PLAN = new PropertyDescriptor.Builder()
		.name("Plan")
		.displayName("Plan")
		.description("A serialized Optic query plan; see https://docs.marklogic.com/guide/app-dev/OpticAPI#id_11208 for information on how to generate a plan")
		.required(true)
		.addValidator(Validator.VALID)
		.expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
		.build();

	public static final PropertyDescriptor MIMETYPE = new PropertyDescriptor.Builder()
		.name("MIME Type")
		.displayName("MIME Type")
		.description("The MIME type to use when returning the rows")
		.defaultValue("text/csv")
		.required(true)
		.addValidator(Validator.VALID)
		.expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
		.build();

	protected static final Relationship FAILURE = new Relationship.Builder().name("failure")
		.description("FlowFiles that were not successfully processed are routed here").build();

	protected static final Relationship SUCCESS = new Relationship.Builder().name("success")
		.description("A FlowFile is routed here with its content being that exported rows").build();
		
	protected static final Relationship ORIGINAL = new Relationship.Builder().name("original")
        .autoTerminateDefault(true)
            .description("If this processor receives a FlowFile, it will be routed to this relationship").build();

	@Override
	public void init(ProcessorInitializationContext context) {
		List<PropertyDescriptor> list = new ArrayList<>();
		list.add(DATABASE_CLIENT_SERVICE);
		list.add(PLAN);
		list.add(MIMETYPE);
		properties = Collections.unmodifiableList(list);

		Set<Relationship> set = new HashSet<>();
		set.add(FAILURE);
		set.add(SUCCESS);
		set.add(ORIGINAL);
		relationships = Collections.unmodifiableSet(set);
	}

	@Override
	public void onTrigger(ProcessContext context, ProcessSessionFactory sessionFactory) throws ProcessException {
		final ProcessSession session = sessionFactory.createSession();
		FlowFile flowFile = session.get();
		FlowFile incomingFlowFile = flowFile;
		try {
			
			if (flowFile == null) {
				flowFile = session.create();
			}

			final String jsonPlan = determineJsonPlan(context, flowFile);
			final String mimeType = determineMimeType(context, flowFile);

			session.putAttribute(flowFile, "marklogic-optic-plan", determineJsonPlan(context, flowFile));

			final DatabaseClient client = getDatabaseClient(context);
			final RowManager rowManager = client.newRowManager();
			PlanBuilder.Plan plan = rowManager.newRawPlanDefinition(new StringHandle(jsonPlan));

			InputStream inputStream = rowManager.resultDoc(plan, new InputStreamHandle().withMimetype(mimeType)).get();
			if (inputStream != null) {
				try {
					if(incomingFlowFile != null) {
						incomingFlowFile = session.write(flowFile, out -> {
							FileCopyUtils.copy(inputStream, out);
						});
					} else {
						flowFile = session.write(flowFile, out -> {
							FileCopyUtils.copy(inputStream, out);
						});
					}
				} finally {
					inputStream.close();
				}
			}
			if(incomingFlowFile != null) {
				transferAndCommit(session, incomingFlowFile, SUCCESS);
			}

			if (flowFile != null) {
                transferAndCommit(session, flowFile, ORIGINAL);
            }
			
		} catch (final Throwable t) {
			// getLogger().error("markLogicErrorMessage", new Object[] { this }, t);
			logErrorAndTransfer(t, flowFile, session, FAILURE);
		}
	}

	protected String determineJsonPlan(ProcessContext context, FlowFile flowFile) {
		return context.getProperty(PLAN).evaluateAttributeExpressions(flowFile).getValue();
	}

	protected String determineMimeType(ProcessContext context, FlowFile flowFile) {
		return context.getProperty(MIMETYPE).evaluateAttributeExpressions(flowFile).getValue();
	}

}
