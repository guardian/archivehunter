import React from 'react';
import PropTypes from 'prop-types';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome';

class JobTypeIcon extends React.Component {
    static propTypes = {
        jobType: PropTypes.string.isRequired
    };

    render(){
        switch(this.props.jobType){
            case "thumbnail":
                return <span data-tip={this.props.jobType}><FontAwesomeIcon icon="image"/></span>;
            default:
                return <span data-tip={this.props.jobType}>{this.props.jobType}</span>;
        }
    }
}

export default JobTypeIcon;