import React from 'react';
import PropTypes from 'prop-types';
import axios from 'axios';
import JobStatusIcon from '../JobsList/JobStatusIcon.jsx';
import LoadingThrobber from '../common/LoadingThrobber.jsx';
import ErrorViewComponent from '../common/ErrorViewComponent.jsx';

class JobEntry extends React.Component {
    static propTypes = {
        jobId: PropTypes.string.isRequired,
        showLink: PropTypes.bool
    };

    constructor(props){
        super(props);

        this.state = {
            loading: false,
            lastError: null,
            jobData: null
        }
    }

    componentWillMount(){
        this.setState({loading:true}, ()=>axios.get("/api/job/" + this.props.jobId)
            .then(response=>{
                this.setState({loading: false, jobData: response.data.entry})
            }).catch(err=>{
                console.error(err);
                this.setState({loading: false, lastError:err});
            }))
    }

    spanBody(){
        return this.props.showLink ? <a href={"/admin/jobs/" + this.state.jobData.jobId}>{this.state.jobData.jobType}</a>: this.state.jobData.jobType;
    }

    render(){
        if(this.state.lastError){
            return <ErrorViewComponent error={this.state.lastError}/>
        } else if(this.state.loading){
            return <LoadingThrobber show={true} />
        } else {
            return this.state.jobData ? <span>
                <JobStatusIcon status={this.state.jobData.jobStatus}/>{this.spanBody()}</span> : <span>{this.props.jobId}</span>
        }
    }
}

export default JobEntry;