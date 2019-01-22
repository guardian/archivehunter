import React from 'react';
import PropTypes from 'prop-types';
import TimestampFormatter from "../common/TimestampFormatter.jsx";
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'

class TranscoderCheckComponent extends React.Component {
    static propTypes = {
        status: PropTypes.string.isRequired,
        checkedAt: PropTypes.string.isRequired
    };

    renderStatusIcon(){
        switch(this.props.status){
            case "ST_SUCCESS":
                return <FontAwesomeIcon icon="check-circle" style={{color: "green", marginRight: "0.2em"}}/>;
            case "ST_FAILURE":
                return <FontAwesomeIcon icon="times-circle" style={{color: "red", marginRight: "0.2em"}}/>;
            default:
                return <FontAwesomeIcon icon="stroopwafel" style={{color: "yellow", marginRight: "0.2em"}}/>;
        }
    }

    render(){
        return <span>
            {
                this.renderStatusIcon()
            }
            <TimestampFormatter relative={true} value={this.props.checkedAt}/>
        </span>
    }
}

export default TranscoderCheckComponent;