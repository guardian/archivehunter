import React from 'react';
import PropTypes from 'prop-types';
import axios from 'axios';
import JobStatusIcon from '../JobsList/JobStatusIcon.jsx';
import LoadingThrobber from '../common/LoadingThrobber.jsx';
import ErrorViewComponent from '../common/ErrorViewComponent.jsx';

class JobEntry extends React.Component {
    static propTypes = {
        jobId: PropTypes.string.isRequired
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
                this.setState({loading: false, jobData: response.data.entity})
            }).catch(err=>{
                console.error(err);
                this.setState({loading: false, lastError:err});
            }))
    }

    render(){
        if(this.state.lastError){
            return <ErrorViewComponent error={this.state.lastError}/>
        } else if(this.state.loading){
            return <LoadingThrobber show={true} />
        } else {
            return this.state.jobData ? <span><JobStatusIcon status={this.state.jobData.jobStatus}/>{this.state.jobData.jobType}</span> : <span>{this.props.jobId}</span>
        }
    }
}

export default JobEntry;