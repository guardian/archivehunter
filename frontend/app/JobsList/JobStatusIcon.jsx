import React from 'react';
import PropTypes from 'prop-types';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome';

class JobStatusIcon extends React.Component {
    static propTypes = {
        status: PropTypes.string.isRequired
    };

    render(){
        switch(this.props.status){
            case "ST_WARNING":
                return <span data-tip="Warning" data-for="jobslist-tooltip"><FontAwesomeIcon size="1.5x" icon="exclamation-triangle" style={{color:"yellow"}}/></span>;
            case "ST_PENDING":
                return <span data-tip="Pending" data-for="jobslist-tooltip"><FontAwesomeIcon size="1.5x" icon="clock"/></span>;
            case "ST_RUNNING":
                return <span data-tip="Running" data-for="jobslist-tooltip"><FontAwesomeIcon size="1.5x" icon="running" style={{color: "blue"}}/></span>;
            case "ST_SUCCESS":
                return <span data-tip="Success" data-for="jobslist-tooltip"><FontAwesomeIcon size="1.5x" icon="check-circle" style={{color:"green"}}/></span>;
            case "ST_ERROR":
                return <span data-tip="Error" data-for="jobslist-tooltip"><FontAwesomeIcon size="1.5x" icon="exclamation-triangle" style={{color:"red"}}/></span>;
            default:
                return <span data-tip={this.props.status}>{this.props.status}</span>;
        }
    }
}

export default JobStatusIcon;