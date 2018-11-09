import React from 'react';
import PropTypes from 'prop-types';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'

class Expander extends React.Component {
    static propTypes = {
        expanded: PropTypes.bool.isRequired,
        onChange: PropTypes.func.isRequired
    };

    constructor(props){
      super(props);

      this.clickedIcon = this.clickedIcon.bind(this);
    }

    clickedIcon(){
        this.props.onChange(!this.props.expanded);
    }

    render(){
        return <FontAwesomeIcon icon={this.props.expanded ? "chevron-circle-down" : "chevron-circle-right"} onClick={this.clickedIcon} style={{cursor: "pointer"}}/>
    }
}

export default Expander;