import React from 'react';
import PropTypes from 'prop-types';
import {FontAwesomeIcon} from "@fortawesome/react-fontawesome";
import axios from "axios";
import ErrorViewComponent from "../common/ErrorViewComponent.jsx";

class ResubmitComponent extends React.Component {
    static propTypes = {
        jobId: PropTypes.string.isRequired,
        visible: PropTypes.bool.isRequired,
        onSuccess: PropTypes.func,
        onFailed: PropTypes.func
    };

    constructor(props){
        super(props);

        this.state = {
            loading: false,
            lastError: null,
            success: false
        };

        this.resubmit = this.resubmit.bind(this);
    }

    resubmit(){
        this.setState({loading:true, lastError: null}, ()=>axios.put("/api/job/rerunproxy/" + this.props.jobId)
            .then(response=>{
                this.setState({loading: false, lastError: null, success: true, attempted: true}, ()=>{
                    if(this.props.onSuccess) this.props.onSuccess(response.data.entry);
                });
            })
            .catch(err=>{
                console.error(err);
                this.setState({loading: false, lastError: err, success:false, attempted: true}, ()=>{
                    if(this.props.onFailed) this.props.onFailed(err);
                });
            }));
    }

    render(){
        return <span style={{display: this.props.visible ? "inherit": "none"}}>
            <FontAwesomeIcon icon="sync-alt" style={{display: this.state.success ? "none":"inherit"}}
                             className={this.state.loading ? "spin" : "button-icon"}
                             onClick={this.resubmit}/>
            <FontAwesomeIcon icon="check" style={{color: "green", display: this.state.success ? "inherit" : "none"}}/>
            <FontAwesomeIcon icon="exclamation-triangle" style={{color: this.state.success ? "yellow": "red", display: !this.state.loading && this.state.lastError!=null ? "inherit" : "none"}}/>
            <ErrorViewComponent error={this.state.lastError} brief={true} />
        </span>
    }
}

export default ResubmitComponent;