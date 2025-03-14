/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { PureComponent } from "react";
import $ from "jquery";
import classNames from "clsx";
import Immutable from "immutable";
import PropTypes from "prop-types";
import { injectIntl } from "react-intl";
import socket from "@inject/utils/socket";
import { flexColumnContainer } from "#oss/uiTheme/less/layout.less";

import ViewStateWrapper from "components/ViewStateWrapper";
import ViewCheckContent from "components/ViewCheckContent";
import JobsContentMixin, {
  MIN_LEFT_PANEL_WIDTH,
  SEPARATOR_WIDTH,
} from "#oss/pages/JobPage/components/JobsContentMixin";
import JobTable from "./JobsTable/JobTable";
import JobDetailsWrapper from "./JobDetails/JobDetailsWrapper";
import JobsFilters from "./JobsFilters/JobsFilters";

// export this for calculate min width of table tr in JobTable.js
export { SEPARATOR_WIDTH, MIN_LEFT_PANEL_WIDTH };

@JobsContentMixin
export class JobsContent extends PureComponent {
  static propTypes = {
    jobId: PropTypes.string,
    jobs: PropTypes.instanceOf(Immutable.List).isRequired,
    queryState: PropTypes.instanceOf(Immutable.Map).isRequired,
    next: PropTypes.string,
    onUpdateQueryState: PropTypes.func.isRequired,
    viewState: PropTypes.instanceOf(Immutable.Map),
    dataWithItemsForFilters: PropTypes.object,
    isNextJobsInProgress: PropTypes.bool,
    location: PropTypes.object,
    intl: PropTypes.object.isRequired,
    className: PropTypes.string,

    loadItemsForFilter: PropTypes.func,
    loadNextJobs: PropTypes.func,
  };

  static defaultProps = {
    jobs: Immutable.List(),
  };

  static contextTypes = {
    router: PropTypes.object,
  };

  constructor(props) {
    super(props);
    this.handleResizeJobs = this.handleResizeJobs.bind(this);
    this.getActiveJob = this.getActiveJob.bind(this);
    this.handleMouseReleaseOutOfBrowser =
      this.handleMouseReleaseOutOfBrowser.bind(this);

    this.handleStartResize = this.handleStartResize.bind(this);
    this.handleEndResize = this.handleEndResize.bind(this);
    this.setActiveJob = this.setActiveJob.bind(this);

    this.state = {
      isResizing: false,
      width: "calc(50% - 22px)",
      left: "calc(50% - 22px)",
      curId: "",
    };
  }

  componentDidMount() {
    $(window).on("mouseup", this.handleMouseReleaseOutOfBrowser);
  }

  UNSAFE_componentWillReceiveProps(nextProps) {
    if (nextProps.jobs !== this.props.jobs) {
      this.runActionForJobs(nextProps.jobs, false, (jobId) =>
        socket.startListenToJobProgress(jobId),
      );
      // if we don't have an active job id highlight the first job
      if (!nextProps.jobId) {
        this.setActiveJob(nextProps.jobs.get(0), true);
      }
    }
  }

  componentWillUnmount() {
    $(window).off("mouseup", this.handleMouseReleaseOutOfBrowser);
    this.runActionForJobs(this.props.jobs, true, (jobId) => {
      return socket.stopListenToJobProgress(jobId);
    });
  }

  render() {
    const {
      jobId,
      jobs,
      queryState,
      onUpdateQueryState,
      viewState,
      location,
      intl,
      className,
    } = this.props;
    const query = location.query || {};
    const styles = this.styles;
    const resizeStyle = this.state.isResizing ? styles.noSelection : {};

    return (
      <div
        className={classNames("jobs-content", flexColumnContainer, className)}
        style={{ ...styles.base, ...resizeStyle }}
      >
        <JobsFilters
          queryState={queryState}
          onUpdateQueryState={onUpdateQueryState}
          style={styles.filters}
          loadItemsForFilter={this.props.loadItemsForFilter}
          dataWithItemsForFilters={this.props.dataWithItemsForFilters}
        />
        <ViewStateWrapper viewState={viewState} style={styles.viewState}>
          <ViewCheckContent
            viewState={viewState}
            message={intl.formatMessage({ id: "Job.NoMatchingJobsFound" })}
            dataIsNotAvailable={!jobs.size}
          >
            <div
              className="job-wrapper"
              style={styles.jobWrapper}
              onMouseMove={this.handleResizeJobs}
              onMouseUp={this.handleEndResize}
            >
              <JobTable
                isNextJobsInProgress={this.props.isNextJobsInProgress}
                loadNextJobs={this.props.loadNextJobs}
                jobs={jobs}
                next={this.props.next}
                width={this.state.width}
                viewState={viewState}
                setActiveJob={this.setActiveJob}
                isResizing={this.state.isResizing}
                containsTextValue={query.contains ? query.contains : ""}
                jobId={jobId}
              />
              <div
                className="separator"
                style={{ ...styles.separator, left: this.state.left }}
                onMouseDown={this.handleStartResize}
              ></div>

              <JobDetailsWrapper jobId={jobId} location={this.props.location} />
            </div>
          </ViewCheckContent>
        </ViewStateWrapper>
      </div>
    );
  }
}
JobsContent = injectIntl(JobsContent);
export default JobsContent;
